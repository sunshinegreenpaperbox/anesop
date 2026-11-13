package com.anesop.m2.impl.acquisition;

import com.anesop.contracts.scheduling.model.OperatingRoom;
import com.anesop.contracts.scheduling.model.OperatingRoomList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.acquisition.AcquisitionConstants;
import com.anesop.m2.api.acquisition.AcquisitionService;
import com.anesop.m2.api.acquisition.DeviceCounts;
import com.anesop.m2.api.acquisition.DeviceHealth;
import com.anesop.m2.api.acquisition.DeviceList;
import com.anesop.m2.api.acquisition.DeviceRegisterRequest;
import com.anesop.m2.api.acquisition.DeviceView;
import com.anesop.m2.api.acquisition.DeviceVital;
import com.anesop.m2.api.acquisition.GatewayHealth;
import com.anesop.m2.api.acquisition.IngestFrame;
import com.anesop.m2.api.acquisition.IngestRequest;
import com.anesop.m2.api.acquisition.IngestResult;
import com.anesop.m2.api.acquisition.IngestSummary;
import com.anesop.m2.api.acquisition.PageMetaView;
import com.anesop.m2.api.acquisition.QualityStatsView;
import com.anesop.m2.api.acquisition.RegisterOutcome;
import com.anesop.m2.api.acquisition.Rejection;
import com.anesop.m2.api.acquisition.VitalPage;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.config.AcquisitionProperties;
import com.anesop.m2.impl.persistence.entity.DeviceRegistryRow;
import com.anesop.m2.impl.persistence.entity.GatewayIngestStatsRow;
import com.anesop.m2.impl.persistence.entity.GatewayQualityRow;
import com.anesop.m2.impl.persistence.entity.VitalInsertOutcome;
import com.anesop.m2.impl.persistence.entity.VitalInsertRow;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaVitalMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceIngestLogMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceRegistryMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备采集服务实现（M3-M8-01，契约 device.yaml 0.1.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>数据范围 fail-closed（M3-M8-01 复审 B-1）：注入 m0 {@link DataScopeResolver}，
 *       非 ALL 范围经 m1-api {@link SchedulingService#listRooms} 取可见术间集合；台账/体征
 *       只返回可见术间设备（无可见术间 403），单设备越界 404（不泄露存在性），登记/换台
 *       目标术间必须可见，未绑定术间仅 ALL（全院）可操作；</li>
 *   <li>登记唯一 (gatewayId, deviceId)：先查后插 + 数据库唯一键兜底（并发 409）；
 *       换台绑定用 version 条件更新，冲突 409；缺省 status 不覆盖已有状态（MAINTENANCE
 *       不会被重复登记改回 ONLINE）；</li>
 *   <li>上报幂等两层：批次层 {@code device_ingest_log} 唯一键整批去重，行层
 *       {@code anesthesia_vital} 唯一键逐帧去重；两层都在同一事务，日志计数回填；</li>
 *   <li>质量优先：只有格式非法（序号/时间/质量码/物理护栏/来源/标记超长）才拒绝；
 *       可疑/缺失帧照常入库并打 quality 与 qualityFlags；健康摘要的质量比例分子/分母
 *       使用同一时间窗（{@code measured_at ∈ [now−窗口, now]}，窗口默认 24h 可配置 1–168）；</li>
 *   <li>{@code last_seen_at} 仅在本批 {@code acceptedRows>0} 时推进（全部重复/拒绝的批次
 *       不刷新在线时间）；离线判定 = lastSeenAt + 阈值推导，MAINTENANCE 不参与推导；</li>
 *   <li>审计 DEVICE_REGISTER/DEVICE_INGEST/DEVICE_QUERY，detail 只含设备/批次标识与计数，
 *       不含网关令牌、报文原文与患者标识；</li>
 *   <li>日志不含令牌与报文原文，错误文案固定。</li>
 * </ul>
 */
@Service
public class AcquisitionServiceImpl implements AcquisitionService {

    private static final Logger LOG = LoggerFactory.getLogger(AcquisitionServiceImpl.class);

    private static final String ACTION_REGISTER = "DEVICE_REGISTER";
    private static final String ACTION_INGEST = "DEVICE_INGEST";
    private static final String ACTION_QUERY = "DEVICE_QUERY";
    private static final String RESOURCE_DEVICE = "DEVICE_REGISTRY";
    private static final String RESOURCE_VITAL = "DEVICE_VITAL";
    private static final String AUDIT_ACTOR_GATEWAY = "gateway";
    private static final String KEY_PATTERN = "[A-Za-z0-9._-]{1,64}";
    private static final String BATCH_KEY_PATTERN = "[A-Za-z0-9._:-]{1,128}";
    private static final Set<String> DEVICE_TYPES = Set.of(
            AcquisitionConstants.TYPE_MONITOR, AcquisitionConstants.TYPE_ANESTHESIA_MACHINE,
            AcquisitionConstants.TYPE_INFUSION_PUMP, AcquisitionConstants.TYPE_VENTILATOR,
            AcquisitionConstants.TYPE_OTHER);
    private static final Set<String> DEVICE_STATUSES = Set.of(
            AcquisitionConstants.STATUS_ONLINE, AcquisitionConstants.STATUS_OFFLINE,
            AcquisitionConstants.STATUS_MAINTENANCE);
    private static final Set<String> SOURCES = Set.of(
            AcquisitionConstants.SOURCE_AUTO, AcquisitionConstants.SOURCE_MANUAL,
            AcquisitionConstants.SOURCE_REPLAY);
    private static final int RATE_SCALE = 6;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final DeviceRegistryMapper registryMapper;
    private final DeviceIngestLogMapper ingestLogMapper;
    private final AnesthesiaVitalMapper vitalMapper;
    private final GatewayTokenVerifier tokenVerifier;
    private final AcquisitionProperties properties;
    private final AuditWriter auditWriter;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final DataScopeResolver dataScopeResolver;
    private final SchedulingService schedulingService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 构造服务。
     *
     * @param registryMapper  设备登记 Mapper
     * @param ingestLogMapper 上报日志 Mapper
     * @param vitalMapper     体征 Mapper
     * @param tokenVerifier   网关令牌校验
     * @param properties      采集配置
     * @param auditWriter     审计写入（m0-api）
     * @param clock           服务端时钟（测试可注入固定时钟）
     * @param objectMapper    JSON 解析（质量统计 jsonb 文本）
     * @param dataScopeResolver 数据范围解析（m0-api）
     * @param schedulingService 可见术间查询（m1-api，不访问 m1 表）
     * @param eventPublisher   领域事件发布（ingest 提交后触发告警评估，AFTER_COMMIT）
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/写入器由容器管理；ObjectMapper 防御性拷贝")
    public AcquisitionServiceImpl(DeviceRegistryMapper registryMapper,
            DeviceIngestLogMapper ingestLogMapper,
            AnesthesiaVitalMapper vitalMapper,
            GatewayTokenVerifier tokenVerifier,
            AcquisitionProperties properties,
            AuditWriter auditWriter,
            Clock clock,
            ObjectMapper objectMapper,
            DataScopeResolver dataScopeResolver,
            SchedulingService schedulingService,
            ApplicationEventPublisher eventPublisher) {
        this.registryMapper = registryMapper;
        this.ingestLogMapper = ingestLogMapper;
        this.vitalMapper = vitalMapper;
        this.tokenVerifier = tokenVerifier;
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.clock = clock;
        this.objectMapper = objectMapper.copy();
        this.dataScopeResolver = dataScopeResolver;
        this.schedulingService = schedulingService;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------- 设备台账 --

    @Override
    public DeviceList listDevices(String gatewayId, String status, UUID roomId, AuthPrincipal caller,
            AuthRequestContext context) {
        String gatewayFilter = gatewayId == null || gatewayId.isBlank() ? null : gatewayId.trim();
        if (gatewayFilter != null && !gatewayFilter.matches(KEY_PATTERN)) {
            throw new M2ValidationException("gatewayId", "网关标识格式不正确");
        }
        if (status != null && !status.isBlank() && !DEVICE_STATUSES.contains(status)) {
            throw new M2ValidationException("status", "设备状态只能是 ONLINE/OFFLINE/MAINTENANCE");
        }
        Instant now = clock.instant();
        Set<UUID> visibleRooms = visibleRoomIds(caller);
        if (visibleRooms != null && roomId != null && !visibleRooms.contains(roomId)) {
            // 集合查询语义：过滤对象越权 403（不泄露该术间是否存在设备）。
            throw new AccessDeniedException("术间超出当前用户数据范围");
        }
        List<DeviceView> items = new ArrayList<>();
        for (DeviceRegistryRow row : registryMapper.selectDevices(gatewayFilter, roomId)) {
            if (!visible(visibleRooms, row.getRoomId())) {
                continue;
            }
            DeviceView view = toView(row, now);
            if (status == null || status.isBlank() || status.equals(view.status())) {
                items.add(view);
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "LIST_DEVICES");
        if (gatewayFilter != null) {
            detail.put("gatewayId", gatewayFilter);
        }
        if (status != null && !status.isBlank()) {
            detail.put("status", status);
        }
        if (roomId != null) {
            detail.put("roomId", roomId.toString());
        }
        detail.put("count", items.size());
        audit(ACTION_QUERY, RESOURCE_DEVICE, gatewayFilter == null ? "ALL" : gatewayFilter, detail,
                caller, context, caller == null ? null : caller.username());
        return new DeviceList(items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RegisterOutcome register(DeviceRegisterRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        String gatewayId = requireDeviceKey(request.gatewayId(), "gatewayId");
        String deviceId = requireDeviceKey(request.deviceId(), "deviceId");
        String deviceType = requireDeviceType(request.deviceType());
        Set<UUID> visibleRooms = visibleRoomIds(caller);
        requireRoomVisible(visibleRooms, request.roomId());
        Instant now = clock.instant();

        DeviceRegistryRow existing = registryMapper.selectByKey(gatewayId, deviceId);
        if (existing == null) {
            // 首次登记：缺省状态 ONLINE；显式值以请求为准（运维可置 OFFLINE/MAINTENANCE）。
            String status = requireStatus(request.status());
            DeviceRegistryRow row = new DeviceRegistryRow();
            row.setId(UUID.randomUUID());
            row.setGatewayId(gatewayId);
            row.setDeviceId(deviceId);
            row.setRoomId(request.roomId());
            row.setDeviceType(deviceType);
            row.setStatus(status);
            row.setCreatedAt(now);
            row.setCreatedBy(actorId(caller));
            row.setUpdatedAt(now);
            row.setUpdatedBy(actorId(caller));
            row.setVersion(1L);
            try {
                registryMapper.insert(row);
            } catch (DuplicateKeyException concurrentCreate) {
                throw new M2ConflictException("设备已被并发登记，请刷新后重试");
            }
            DeviceRegistryRow saved = registryMapper.selectByKey(gatewayId, deviceId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("operation", "REGISTER");
            detail.put("gatewayId", gatewayId);
            detail.put("deviceId", deviceId);
            detail.put("deviceType", deviceType);
            detail.put("status", status);
            if (request.roomId() != null) {
                detail.put("roomId", request.roomId().toString());
            }
            audit(ACTION_REGISTER, RESOURCE_DEVICE, deviceId, detail, caller, context,
                    caller == null ? null : caller.username());
            return new RegisterOutcome(toView(saved == null ? row : saved, now), true);
        }

        if (!visible(visibleRooms, existing.getRoomId())) {
            throw new AccessDeniedException("设备超出当前用户数据范围");
        }
        // 缺省不覆盖已有运维状态：重复登记/换台不带 status 时保留 MAINTENANCE 等显式状态。
        String status = request.status() == null || request.status().isBlank()
                ? existing.getStatus() : requireStatus(request.status());
        boolean unchanged = Objects.equals(existing.getRoomId(), request.roomId())
                && Objects.equals(existing.getDeviceType(), deviceType)
                && Objects.equals(existing.getStatus(), status);
        if (unchanged) {
            // 幂等登记：不产生新行、不递增版本、不重复审计（无状态变更）。
            return new RegisterOutcome(toView(existing, now), false);
        }
        int updated = registryMapper.updateBinding(gatewayId, deviceId, existing.getVersion(),
                request.roomId(), deviceType, status, now, actorId(caller));
        if (updated == 0) {
            throw new M2ConflictException("设备登记已被修改，请刷新后重试");
        }
        DeviceRegistryRow saved = registryMapper.selectByKey(gatewayId, deviceId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "REBIND");
        detail.put("gatewayId", gatewayId);
        detail.put("deviceId", deviceId);
        detail.put("deviceType", deviceType);
        detail.put("status", status);
        detail.put("roomId", request.roomId() == null ? null : request.roomId().toString());
        audit(ACTION_REGISTER, RESOURCE_DEVICE, deviceId, detail, caller, context,
                caller == null ? null : caller.username());
        return new RegisterOutcome(toView(saved == null ? existing : saved, now), false);
    }

    // ------------------------------------------------------------- 网关上报 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestResult ingest(String gatewayId, String deviceId, String gatewayToken,
            IngestRequest request, AuthRequestContext context) {
        tokenVerifier.verify(gatewayToken);
        String gateway = requireDeviceKey(gatewayId, "gatewayId");
        String device = requireDeviceKey(deviceId, "deviceId");
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        String batchKey = requireBatchKey(request.batchKey());
        List<IngestFrame> frames = request.frames();
        if (frames.isEmpty() || frames.size() > AcquisitionConstants.MAX_FRAMES_PER_BATCH) {
            throw new M2ValidationException("frames", "单批帧数必须在 1–500 之间");
        }
        DeviceRegistryRow registry = registryMapper.selectByKey(gateway, device);
        if (registry == null) {
            throw new NoSuchElementException("设备未登记或不存在");
        }

        Instant receivedAt = clock.instant();
        int claimed = ingestLogMapper.insertIgnoreDuplicate(gateway, device, batchKey, receivedAt);
        if (claimed == 0) {
            // 重复批次：整批丢弃，不重复入库、不重复累加质量统计。
            IngestResult duplicate = new IngestResult(gateway, device, batchKey, 0, frames.size(), 0,
                    true, List.of(), receivedAt);
            Map<String, Object> detail = ingestDetail(duplicate, 0, 0);
            audit(ACTION_INGEST, RESOURCE_DEVICE, device, detail, null, context, AUDIT_ACTOR_GATEWAY);
            return duplicate;
        }

        List<Rejection> rejections = new ArrayList<>();
        List<VitalInsertRow> valid = new ArrayList<>();
        for (int index = 0; index < frames.size(); index++) {
            IngestFrame frame = frames.get(index);
            String reason = rejectReason(frame);
            if (reason != null) {
                rejections.add(new Rejection(index, reason));
            } else {
                valid.add(toInsertRow(gateway, device, frame, receivedAt, properties.synthetic()));
            }
        }
        VitalInsertOutcome outcome = valid.isEmpty() ? null : vitalMapper.insertFrames(valid);
        long inserted = outcome == null || outcome.getInserted() == null ? 0L : outcome.getInserted();
        long duplicates = valid.size() - inserted;
        long suspect = outcome == null || outcome.getSuspectRows() == null ? 0L : outcome.getSuspectRows();
        long missing = outcome == null || outcome.getMissingRows() == null ? 0L : outcome.getMissingRows();

        ingestLogMapper.updateCounts(gateway, batchKey, inserted, duplicates, rejections.size());
        registryMapper.markSeen(gateway, device, receivedAt, receivedAt.toEpochMilli(), batchKey,
                inserted, duplicates, rejections.size(), suspect, missing);

        IngestResult result = new IngestResult(gateway, device, batchKey, inserted, duplicates,
                rejections.size(), false, rejections, receivedAt);
        audit(ACTION_INGEST, RESOURCE_DEVICE, device, ingestDetail(result, suspect, missing), null,
                context, AUDIT_ACTOR_GATEWAY);
        if (inserted > 0) {
            // 领域事件在事务内发布，@TransactionalEventListener(AFTER_COMMIT) 在提交后评估；
            // 采集回滚时不产生告警（评审 B-1 / ADR-0017）。
            eventPublisher.publishEvent(new VitalsIngestedEvent(gateway, device, batchKey,
                    inserted, receivedAt));
        }
        return result;
    }

    // ------------------------------------------------------------- 体征查询 --

    @Override
    public VitalPage queryVitals(String gatewayId, String deviceId, Instant from, Instant to,
            Integer quality, Integer page, Integer size, AuthPrincipal caller,
            AuthRequestContext context) {
        String gateway = requireDeviceKey(gatewayId, "gatewayId");
        String device = requireDeviceKey(deviceId, "deviceId");
        if (from == null || to == null) {
            throw new M2ValidationException("from", "查询窗口起止时间不能为空");
        }
        if (!from.isBefore(to)) {
            throw new M2ValidationException("to", "窗口终点必须晚于起点");
        }
        long maxSeconds = properties.maxQueryWindowHours() * 3600L;
        if (to.getEpochSecond() - from.getEpochSecond() > maxSeconds) {
            throw new M2UnprocessableException("to",
                    "查询窗口跨度不能超过 " + properties.maxQueryWindowHours() + " 小时");
        }
        if (quality != null && (quality < AcquisitionConstants.QUALITY_NORMAL
                || quality > AcquisitionConstants.QUALITY_TIMEOUT)) {
            throw new M2ValidationException("quality", "质量码必须在 0–3 之间");
        }
        int pageNumber = page == null ? 1 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageNumber < 1) {
            throw new M2ValidationException("page", "页码必须从 1 开始");
        }
        if (pageSize < 1 || pageSize > AcquisitionConstants.MAX_PAGE_SIZE) {
            throw new M2ValidationException("size", "每页条数必须在 1–200 之间");
        }
        Set<UUID> visibleRooms = visibleRoomIds(caller);
        DeviceRegistryRow registry = registryMapper.selectByKey(gateway, device);
        if (registry == null || !visible(visibleRooms, registry.getRoomId())) {
            // 单对象语义：不存在与越界统一 404，不泄露设备存在性。
            throw new NoSuchElementException("设备未登记或不存在");
        }

        long total = vitalMapper.countVitals(gateway, device, from, to, quality);
        long offset = (long) (pageNumber - 1) * pageSize;
        List<DeviceVital> items = new ArrayList<>();
        if (offset < total) {
            for (VitalRow row : vitalMapper.selectVitals(gateway, device, from, to, quality,
                    pageSize, offset)) {
                items.addAll(flatten(row));
            }
        }
        int totalPages = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "QUERY_VITALS");
        detail.put("gatewayId", gateway);
        detail.put("deviceId", device);
        detail.put("from", from.toString());
        detail.put("to", to.toString());
        if (quality != null) {
            detail.put("quality", quality);
        }
        detail.put("page", pageNumber);
        detail.put("size", pageSize);
        detail.put("frames", total);
        audit(ACTION_QUERY, RESOURCE_VITAL, gateway + "/" + device, detail, caller, context,
                caller == null ? null : caller.username());
        return new VitalPage(items, new PageMetaView(pageNumber, pageSize, total, totalPages));
    }

    // ------------------------------------------------------------- 健康摘要 --

    @Override
    public DeviceHealth health() {
        Instant now = clock.instant();
        Instant since = now.minusSeconds(properties.healthWindowHours() * 3600L);
        List<DeviceRegistryRow> registryRows = registryMapper.selectDevices(null, null);
        Map<String, List<DeviceRegistryRow>> byGateway = new LinkedHashMap<>();
        for (DeviceRegistryRow row : registryRows) {
            byGateway.computeIfAbsent(row.getGatewayId(), key -> new ArrayList<>()).add(row);
        }
        Map<String, GatewayIngestStatsRow> ingestByGateway = new LinkedHashMap<>();
        for (GatewayIngestStatsRow row : ingestLogMapper.statsSince(since, now)) {
            ingestByGateway.put(row.getGatewayId(), row);
        }
        Map<String, GatewayQualityRow> qualityByGateway = new LinkedHashMap<>();
        for (GatewayQualityRow row : vitalMapper.qualitySince(since, now)) {
            qualityByGateway.put(row.getGatewayId(), row);
        }

        Set<String> gatewayIds = new LinkedHashSet<>(byGateway.keySet());
        gatewayIds.addAll(ingestByGateway.keySet());
        gatewayIds.addAll(qualityByGateway.keySet());

        int online = 0;
        int offline = 0;
        int maintenance = 0;
        List<GatewayHealth> gateways = new ArrayList<>();
        boolean degraded = false;
        long totalBatches = 0;
        long totalAccepted = 0;
        long totalDuplicate = 0;
        long totalRejected = 0;
        Instant lastIngestAt = null;
        for (String gatewayId : gatewayIds) {
            List<DeviceRegistryRow> rows = byGateway.getOrDefault(gatewayId, List.of());
            int devicesOnline = 0;
            int devicesOffline = 0;
            int devicesMaintenance = 0;
            Instant lastSeenAt = null;
            for (DeviceRegistryRow row : rows) {
                String status = deriveStatus(row, now);
                if (AcquisitionConstants.STATUS_MAINTENANCE.equals(status)) {
                    devicesMaintenance++;
                } else if (AcquisitionConstants.STATUS_ONLINE.equals(status)) {
                    devicesOnline++;
                } else {
                    devicesOffline++;
                }
                if (row.getLastSeenAt() != null
                        && (lastSeenAt == null || row.getLastSeenAt().isAfter(lastSeenAt))) {
                    lastSeenAt = row.getLastSeenAt();
                }
            }
            online += devicesOnline;
            offline += devicesOffline;
            maintenance += devicesMaintenance;

            GatewayIngestStatsRow stats = ingestByGateway.get(gatewayId);
            long batches = stats == null || stats.getBatches() == null ? 0 : stats.getBatches();
            long accepted = stats == null || stats.getAcceptedRows() == null ? 0 : stats.getAcceptedRows();
            long duplicate = stats == null || stats.getDuplicateRows() == null ? 0 : stats.getDuplicateRows();
            long rejected = stats == null || stats.getRejectedRows() == null ? 0 : stats.getRejectedRows();
            Instant gatewayLastIngest = stats == null ? null : stats.getLastReceivedAt();
            if (gatewayLastIngest != null && (lastIngestAt == null || gatewayLastIngest.isAfter(lastIngestAt))) {
                lastIngestAt = gatewayLastIngest;
            }
            GatewayQualityRow quality = qualityByGateway.get(gatewayId);
            long qualityTotal = quality == null || quality.getTotalRows() == null ? 0 : quality.getTotalRows();
            long suspect = quality == null || quality.getSuspectRows() == null ? 0 : quality.getSuspectRows();
            long missing = quality == null || quality.getMissingRows() == null ? 0 : quality.getMissingRows();
            double qualityRate = ratio(suspect + missing, qualityTotal);

            String gatewayStatus;
            if (gatewayLastIngest == null) {
                gatewayStatus = "NO_INGEST";
            } else if (gatewayLastIngest.plusSeconds(properties.offlineTimeoutSeconds()).isAfter(now)) {
                gatewayStatus = "ONLINE";
            } else {
                gatewayStatus = "STALE";
            }
            if (!"ONLINE".equals(gatewayStatus) || rejected > 0) {
                degraded = true;
            }
            totalBatches += batches;
            totalAccepted += accepted;
            totalDuplicate += duplicate;
            totalRejected += rejected;
            gateways.add(new GatewayHealth(gatewayId, gatewayStatus, rows.size(), devicesOnline,
                    devicesOffline, devicesMaintenance, lastSeenAt, gatewayLastIngest, batches,
                    accepted, duplicate, rejected, qualityRate));
        }

        String status;
        if (registryRows.isEmpty()) {
            status = "NO_DATA";
        } else if (degraded) {
            status = "DEGRADED";
        } else {
            status = "HEALTHY";
        }
        return new DeviceHealth(now, properties.offlineTimeoutSeconds(), status,
                new DeviceCounts(registryRows.size(), online, offline, maintenance),
                new IngestSummary(totalBatches, totalAccepted, totalDuplicate, totalRejected, lastIngestAt),
                gateways);
    }

    // --------------------------------------------------------------- helpers --

    /**
     * 当前调用者可见的术间集合。
     *
     * @return {@code null} 表示全院可见（ALL，含未绑定术间的设备）；否则为非空可见术间集合
     * @throws AccessDeniedException 无数据范围、无可见术间或缺少调用者身份（fail-closed）
     */
    private Set<UUID> visibleRoomIds(AuthPrincipal caller) {
        if (caller == null) {
            throw new AccessDeniedException("缺少调用者身份");
        }
        DataScopeAssignments scope = dataScopeResolver.resolve(caller.userId());
        if (scope.isDenied()) {
            throw new AccessDeniedException("当前用户无可用数据范围");
        }
        if (scope.isUnrestricted()) {
            return null;
        }
        OperatingRoomList rooms = schedulingService.listRooms(caller, null, null);
        Set<UUID> ids = new LinkedHashSet<>();
        for (OperatingRoom room : rooms.getItems()) {
            UUID roomId = parseUuidQuietly(room.getRoomId());
            if (roomId != null) {
                ids.add(roomId);
            }
        }
        if (ids.isEmpty()) {
            throw new AccessDeniedException("当前用户无可用术间数据范围");
        }
        return ids;
    }

    /** 设备是否在可见范围内：未绑定术间的设备只有 ALL 可见。 */
    private static boolean visible(Set<UUID> visibleRooms, UUID roomId) {
        return visibleRooms == null || (roomId != null && visibleRooms.contains(roomId));
    }

    /** 登记/换台的目标术间必须可见；未绑定（roomId=null）仅 ALL 允许。 */
    private static void requireRoomVisible(Set<UUID> visibleRooms, UUID roomId) {
        if (visibleRooms == null) {
            return;
        }
        if (roomId == null || !visibleRooms.contains(roomId)) {
            throw new AccessDeniedException("目标术间超出当前用户数据范围");
        }
    }

    private static UUID parseUuidQuietly(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private DeviceView toView(DeviceRegistryRow row, Instant now) {
        return new DeviceView(row.getGatewayId(), row.getDeviceId(), row.getRoomId(),
                row.getDeviceType(), deriveStatus(row, now), row.getLastSeenAt(),
                parseStats(row.getQualityStats()), row.getCreatedAt(), row.getUpdatedAt(),
                row.getVersion() == null ? 0L : row.getVersion());
    }

    private String deriveStatus(DeviceRegistryRow row, Instant now) {
        if (AcquisitionConstants.STATUS_MAINTENANCE.equals(row.getStatus())) {
            return AcquisitionConstants.STATUS_MAINTENANCE;
        }
        Instant lastSeen = row.getLastSeenAt();
        if (lastSeen == null) {
            return AcquisitionConstants.STATUS_OFFLINE;
        }
        return lastSeen.plusSeconds(properties.offlineTimeoutSeconds()).isAfter(now)
                ? AcquisitionConstants.STATUS_ONLINE
                : AcquisitionConstants.STATUS_OFFLINE;
    }

    private QualityStatsView parseStats(String json) {
        long batches = 0L;
        long accepted = 0L;
        long duplicate = 0L;
        long rejected = 0L;
        long suspect = 0L;
        long missing = 0L;
        String lastBatchKey = null;
        Instant lastBatchAt = null;
        if (json != null && !json.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(json);
                batches = node.path("batches").asLong(0L);
                accepted = node.path("acceptedRows").asLong(0L);
                duplicate = node.path("duplicateRows").asLong(0L);
                rejected = node.path("rejectedRows").asLong(0L);
                suspect = node.path("suspectRows").asLong(0L);
                missing = node.path("missingRows").asLong(0L);
                if (node.hasNonNull("lastBatchKey")) {
                    lastBatchKey = node.get("lastBatchKey").asText();
                }
                long lastBatchAtMs = node.path("lastBatchAtMs").asLong(0L);
                if (lastBatchAtMs > 0L) {
                    lastBatchAt = Instant.ofEpochMilli(lastBatchAtMs);
                }
            } catch (JsonProcessingException damaged) {
                // 统计损坏不阻断台账查询：按零处理即可，日志不含 jsonb 原文。
                LOG.warn("设备质量统计解析失败，按零处理");
            }
        }
        return new QualityStatsView(batches, accepted, duplicate, rejected, suspect, missing,
                ratio(suspect + missing, accepted), ratio(rejected, accepted + rejected), lastBatchKey,
                lastBatchAt);
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0L) {
            return 0d;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static String rejectReason(IngestFrame frame) {
        if (frame == null || frame.seq() == null || frame.seq() < 0L) {
            return AcquisitionConstants.REJECT_SEQ_INVALID;
        }
        if (frame.measuredAt() == null) {
            return AcquisitionConstants.REJECT_MEASURED_AT_MISSING;
        }
        if (frame.quality() == null || frame.quality() < AcquisitionConstants.QUALITY_NORMAL
                || frame.quality() > AcquisitionConstants.QUALITY_TIMEOUT) {
            return AcquisitionConstants.REJECT_QUALITY_OUT_OF_RANGE;
        }
        if (outsideIntRange(frame.hr(), 0, 1000)
                || outsideIntRange(frame.spo2(), 0, 1000)
                || outsideIntRange(frame.nibpSys(), 0, 1000)
                || outsideIntRange(frame.nibpDia(), 0, 1000)
                || outsideDecimalRange(frame.etco2(), 0, 1000)
                || outsideDecimalRange(frame.temp(), -50, 150)) {
            return AcquisitionConstants.REJECT_VALUE_OUT_OF_RANGE;
        }
        if (frame.source() != null && !SOURCES.contains(frame.source())) {
            return AcquisitionConstants.REJECT_SOURCE_INVALID;
        }
        if (String.join(",", frame.flags()).length() > AcquisitionConstants.MAX_QUALITY_FLAGS_LENGTH) {
            return AcquisitionConstants.REJECT_FLAGS_TOO_LONG;
        }
        return null;
    }

    private static boolean outsideIntRange(Integer value, int min, int max) {
        return value != null && (value < min || value > max);
    }

    private static boolean outsideDecimalRange(BigDecimal value, int min, int max) {
        return value != null && (value.doubleValue() < min || value.doubleValue() > max);
    }

    private static VitalInsertRow toInsertRow(String gatewayId, String deviceId, IngestFrame frame,
            Instant receivedAt, boolean synthetic) {
        VitalInsertRow row = new VitalInsertRow();
        row.setGatewayId(gatewayId);
        row.setDeviceId(deviceId);
        row.setSeq(frame.seq());
        row.setMeasuredAt(frame.measuredAt());
        row.setMeasuredAtCorrected(frame.measuredAtCorrected() == null
                ? frame.measuredAt() : frame.measuredAtCorrected());
        row.setReceivedAt(frame.receivedAt() == null ? receivedAt : frame.receivedAt());
        row.setHr(frame.hr());
        row.setSpo2(frame.spo2());
        row.setNibpSys(frame.nibpSys());
        row.setNibpDia(frame.nibpDia());
        row.setEtco2(frame.etco2());
        row.setTemp(frame.temp());
        row.setQuality(frame.quality());
        String flags = String.join(",", frame.flags());
        row.setQualityFlags(flags.isEmpty() ? null : flags);
        row.setSource(frame.source() == null ? AcquisitionConstants.SOURCE_AUTO : frame.source());
        // POC/测试期默认 synthetic=true；接入真实设备后由部署配置改为 false（V4 synthetic 语义）。
        row.setSynthetic(synthetic);
        return row;
    }

    private static List<DeviceVital> flatten(VitalRow row) {
        List<String> flags = parseFlags(row.getQualityFlags());
        int quality = row.getQuality() == null ? 0 : row.getQuality();
        List<DeviceVital> items = new ArrayList<>(6);
        addMetric(items, AcquisitionConstants.METRIC_HR, row.getHr(),
                AcquisitionConstants.UNIT_BPM, row.getMeasuredAt(), quality, flags);
        addMetric(items, AcquisitionConstants.METRIC_SPO2, row.getSpo2(),
                AcquisitionConstants.UNIT_PERCENT, row.getMeasuredAt(), quality, flags);
        addMetric(items, AcquisitionConstants.METRIC_NIBP_SYS, row.getNibpSys(),
                AcquisitionConstants.UNIT_MMHG, row.getMeasuredAt(), quality, flags);
        addMetric(items, AcquisitionConstants.METRIC_NIBP_DIA, row.getNibpDia(),
                AcquisitionConstants.UNIT_MMHG, row.getMeasuredAt(), quality, flags);
        addMetric(items, AcquisitionConstants.METRIC_ETCO2, row.getEtco2(),
                AcquisitionConstants.UNIT_MMHG, row.getMeasuredAt(), quality, flags);
        addMetric(items, AcquisitionConstants.METRIC_TEMP, row.getTemp(),
                AcquisitionConstants.UNIT_CELSIUS, row.getMeasuredAt(), quality, flags);
        return items;
    }

    private static void addMetric(List<DeviceVital> items, String metric, Number value, String unit,
            Instant measuredAt, int quality, List<String> flags) {
        if (value == null) {
            return;
        }
        BigDecimal numeric = value instanceof BigDecimal decimal
                ? decimal
                : BigDecimal.valueOf(value.doubleValue());
        items.add(new DeviceVital(metric, numeric, unit, measuredAt, quality, flags));
    }

    private static List<String> parseFlags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                flags.add(part.trim());
            }
        }
        return flags;
    }

    private Map<String, Object> ingestDetail(IngestResult result, long suspect, long missing) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "INGEST");
        detail.put("gatewayId", result.gatewayId());
        detail.put("deviceId", result.deviceId());
        detail.put("batchKey", result.batchKey());
        detail.put("acceptedRows", result.acceptedRows());
        detail.put("duplicateRows", result.duplicateRows());
        detail.put("rejectedRows", result.rejectedRows());
        detail.put("duplicateBatch", result.duplicateBatch());
        detail.put("suspectRows", suspect);
        detail.put("missingRows", missing);
        return detail;
    }

    private void audit(String action, String resourceType, String resourceId, Map<String, Object> detail,
            AuthPrincipal caller, AuthRequestContext context, String actorName) {
        auditWriter.record(action, resourceType, resourceId, true, detail, actorId(caller), actorName,
                context);
    }

    private static UUID actorId(AuthPrincipal caller) {
        return caller == null ? null : caller.userId();
    }

    private static String requireDeviceKey(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches(KEY_PATTERN)) {
            throw new M2ValidationException(field, "标识必须是 1–64 位字母、数字、点、下划线或短横线");
        }
        return trimmed;
    }

    private static String requireBatchKey(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches(BATCH_KEY_PATTERN)) {
            throw new M2ValidationException("batchKey", "批次键必须是 1–128 位字母、数字或 . _ : -");
        }
        return trimmed;
    }

    private static String requireDeviceType(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!DEVICE_TYPES.contains(trimmed)) {
            throw new M2ValidationException("deviceType", "设备类型不在允许范围内");
        }
        return trimmed;
    }

    private static String requireStatus(String value) {
        if (value == null || value.isBlank()) {
            return AcquisitionConstants.STATUS_ONLINE;
        }
        String trimmed = value.trim();
        if (!DEVICE_STATUSES.contains(trimmed)) {
            throw new M2ValidationException("status", "设备状态只能是 ONLINE/OFFLINE/MAINTENANCE");
        }
        return trimmed;
    }
}
