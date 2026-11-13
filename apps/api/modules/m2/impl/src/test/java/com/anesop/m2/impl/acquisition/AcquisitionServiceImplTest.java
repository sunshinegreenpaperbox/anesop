package com.anesop.m2.impl.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.scheduling.model.OperatingRoom;
import com.anesop.contracts.scheduling.model.OperatingRoomList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.acquisition.AcquisitionConstants;
import com.anesop.m2.api.acquisition.DeviceHealth;
import com.anesop.m2.api.acquisition.DeviceList;
import com.anesop.m2.api.acquisition.DeviceRegisterRequest;
import com.anesop.m2.api.acquisition.DeviceView;
import com.anesop.m2.api.acquisition.GatewayAuthException;
import com.anesop.m2.api.acquisition.IngestFrame;
import com.anesop.m2.api.acquisition.IngestRequest;
import com.anesop.m2.api.acquisition.IngestResult;
import com.anesop.m2.api.acquisition.RegisterOutcome;
import com.anesop.m2.api.acquisition.VitalPage;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.config.AcquisitionProperties;
import com.anesop.m2.impl.persistence.entity.DeviceRegistryRow;
import com.anesop.m2.impl.persistence.entity.GatewayIngestStatsRow;
import com.anesop.m2.impl.persistence.entity.GatewayQualityRow;
import com.anesop.m2.impl.persistence.entity.VitalInsertOutcome;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaVitalMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceIngestLogMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceRegistryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * 设备采集服务单测（M3-M8-01）：用 Mockito 隔离 Mapper/审计，断言可观察行为
 * （返回值、异常语义、统计写参、审计动作），不复制实现。
 *
 * <p>集成路径（真实 PostgreSQL、权限 401/403、审计落库）由
 * {@code DeviceAcquisitionApiIntegrationTest} 覆盖。
 */
class AcquisitionServiceImplTest {

    private static final String GATEWAY = "GW-OR-01";
    private static final String DEVICE = "SIM-MON-01";
    private static final String TOKEN = "synth-gateway-token";
    private static final Instant NOW = Instant.parse("2026-11-14T01:30:00Z");
    private static final UUID ROOM = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000001");

    private static final DataScopeAssignments ALL_SCOPE = DataScopeAssignments.of(
            List.of(new DataScopeAssignment(DataScope.ALL, List.of())));

    private DeviceRegistryMapper registryMapper;
    private DeviceIngestLogMapper ingestLogMapper;
    private AnesthesiaVitalMapper vitalMapper;
    private AuditWriter auditWriter;
    private DataScopeResolver dataScopeResolver;
    private SchedulingService schedulingService;
    private ApplicationEventPublisher eventPublisher;
    private AcquisitionServiceImpl service;

    @BeforeEach
    void setUp() {
        registryMapper = mock(DeviceRegistryMapper.class);
        ingestLogMapper = mock(DeviceIngestLogMapper.class);
        vitalMapper = mock(AnesthesiaVitalMapper.class);
        auditWriter = mock(AuditWriter.class);
        dataScopeResolver = mock(DataScopeResolver.class);
        schedulingService = mock(SchedulingService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        org.mockito.Mockito.lenient().when(dataScopeResolver.resolve(any())).thenReturn(ALL_SCOPE);
        AcquisitionProperties properties = new AcquisitionProperties(TOKEN, 120L, 24L, 24L, true);
        service = new AcquisitionServiceImpl(registryMapper, ingestLogMapper, vitalMapper,
                new GatewayTokenVerifier(properties), properties, auditWriter,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), dataScopeResolver,
                schedulingService, eventPublisher);
    }

    // --------------------------------------------------------------- 设备登记 --

    @Test
    void registerCreatesRowAndAuditsWhenAbsent() {
        DeviceRegistryRow saved = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW.minusSeconds(5));
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(null, saved);

        RegisterOutcome outcome = service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, ROOM,
                        AcquisitionConstants.TYPE_MONITOR, null), principal(), null);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.device().roomId()).isEqualTo(ROOM);
        assertThat(outcome.device().status()).isEqualTo(AcquisitionConstants.STATUS_ONLINE);
        verify(registryMapper).insert(any(DeviceRegistryRow.class));
        assertThat(auditAction()).isEqualTo("DEVICE_REGISTER");
        assertThat(auditDetail()).containsEntry("operation", "REGISTER")
                .containsEntry("gatewayId", GATEWAY)
                .containsEntry("deviceId", DEVICE);
    }

    @Test
    void registerIsIdempotentWhenBindingUnchanged() {
        DeviceRegistryRow existing = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW.minusSeconds(5));
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(existing);

        RegisterOutcome outcome = service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, ROOM,
                        AcquisitionConstants.TYPE_MONITOR, AcquisitionConstants.STATUS_ONLINE),
                principal(), null);

        assertThat(outcome.created()).isFalse();
        verify(registryMapper, never()).insert(any());
        verify(registryMapper, never()).updateBinding(any(), any(), any(), any(), any(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(auditWriter);
    }

    @Test
    void registerRebindsRoomWithOptimisticVersion() {
        DeviceRegistryRow existing = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW.minusSeconds(5));
        UUID newRoom = UUID.fromString("80000000-0000-4000-8000-000000000002");
        DeviceRegistryRow rebound = row(newRoom, AcquisitionConstants.STATUS_MAINTENANCE, NOW.minusSeconds(5));
        rebound.setVersion(2L);
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(existing, rebound);
        when(registryMapper.updateBinding(eq(GATEWAY), eq(DEVICE), eq(1L), eq(newRoom),
                eq(AcquisitionConstants.TYPE_ANESTHESIA_MACHINE), eq(AcquisitionConstants.STATUS_MAINTENANCE),
                any(), eq(ACTOR))).thenReturn(1);

        RegisterOutcome outcome = service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, newRoom,
                        AcquisitionConstants.TYPE_ANESTHESIA_MACHINE, AcquisitionConstants.STATUS_MAINTENANCE),
                principal(), null);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.device().roomId()).isEqualTo(newRoom);
        assertThat(outcome.device().status()).isEqualTo(AcquisitionConstants.STATUS_MAINTENANCE);
        assertThat(auditDetail()).containsEntry("operation", "REBIND");
    }

    @Test
    void registerConflictWhenConcurrentInsertOrStaleVersion() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(null);
        when(registryMapper.insert(any(DeviceRegistryRow.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, ROOM, AcquisitionConstants.TYPE_MONITOR, null),
                principal(), null)).isInstanceOf(M2ConflictException.class);

        DeviceRegistryRow existing = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW.minusSeconds(5));
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(existing);
        when(registryMapper.updateBinding(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, UUID.randomUUID(),
                        AcquisitionConstants.TYPE_MONITOR, null), principal(), null))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void registerRejectsInvalidTypeOrKey() {
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, ROOM, "UNKNOWN", null), principal(), null))
                .isInstanceOf(M2ValidationException.class);
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest("GW/../x", DEVICE, ROOM, AcquisitionConstants.TYPE_MONITOR, null),
                principal(), null)).isInstanceOf(M2ValidationException.class);
    }

    // --------------------------------------------------------------- 设备台账 --

    @Test
    void listDevicesDerivesOfflineFromLastSeenAndFiltersStatus() {
        DeviceRegistryRow online = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW.minusSeconds(10));
        online.setQualityStats("{\"batches\":2,\"acceptedRows\":10,\"suspectRows\":1,"
                + "\"rejectedRows\":1,\"lastBatchKey\":\"K1\",\"lastBatchAtMs\":" + NOW.toEpochMilli() + "}");
        DeviceRegistryRow stale = row(UUID.randomUUID(), AcquisitionConstants.STATUS_OFFLINE,
                NOW.minusSeconds(600));
        DeviceRegistryRow maintenance = row(UUID.randomUUID(),
                AcquisitionConstants.STATUS_MAINTENANCE, null);
        when(registryMapper.selectDevices(null, null)).thenReturn(List.of(online, stale, maintenance));

        DeviceList all = service.listDevices(null, null, null, principal(), null);
        assertThat(all.items()).hasSize(3);
        assertThat(all.items().get(0).status()).isEqualTo(AcquisitionConstants.STATUS_ONLINE);
        assertThat(all.items().get(0).qualityStats().qualityRate()).isEqualTo(0.1d);
        assertThat(all.items().get(0).qualityStats().rejectedRate()).isEqualTo(0.090909d);
        assertThat(all.items().get(0).qualityStats().lastBatchAt()).isEqualTo(NOW);
        assertThat(all.items().get(1).status()).isEqualTo(AcquisitionConstants.STATUS_OFFLINE);
        assertThat(all.items().get(2).status()).isEqualTo(AcquisitionConstants.STATUS_MAINTENANCE);

        DeviceList filtered = service.listDevices(null, AcquisitionConstants.STATUS_OFFLINE, null,
                principal(), null);
        assertThat(filtered.items()).hasSize(1);
        assertThat(filtered.items().get(0).lastSeenAt()).isEqualTo(NOW.minusSeconds(600));
    }

    @Test
    void listDevicesToleratesDamagedQualityStatsAndRejectsBadFilter() {
        DeviceRegistryRow damaged = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW);
        damaged.setQualityStats("not-json");
        when(registryMapper.selectDevices(GATEWAY, null)).thenReturn(List.of(damaged));

        DeviceList list = service.listDevices(GATEWAY, null, null, principal(), null);
        assertThat(list.items().get(0).qualityStats()).isNotNull();
        assertThat(list.items().get(0).qualityStats().acceptedRows()).isZero();

        assertThatThrownBy(() -> service.listDevices(null, "UNKNOWN", null, principal(), null))
                .isInstanceOf(M2ValidationException.class);
    }

    // --------------------------------------------------------------- 网关上报 --

    @Test
    void ingestRejectsBadTokenBeforeTouchingDatabase() {
        assertThatThrownBy(() -> service.ingest(GATEWAY, DEVICE, "wrong-token",
                new IngestRequest("B1", List.of(frame(1, 0))), null))
                .isInstanceOf(GatewayAuthException.class);
        verify(registryMapper, never()).selectByKey(any(), any());
    }

    @Test
    void ingestFailsClosedWhenTokenNotConfigured() {
        AcquisitionProperties noToken = new AcquisitionProperties("", 120L, 24L, 24L, true);
        AcquisitionServiceImpl unconfigured = new AcquisitionServiceImpl(registryMapper, ingestLogMapper,
                vitalMapper, new GatewayTokenVerifier(noToken), noToken, auditWriter,
                Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper(), dataScopeResolver,
                schedulingService, eventPublisher);

        assertThatThrownBy(() -> unconfigured.ingest(GATEWAY, DEVICE, "anything",
                new IngestRequest("B1", List.of(frame(1, 0))), null))
                .isInstanceOf(GatewayAuthException.class);
    }

    @Test
    void duplicateBatchIsDiscardedWithoutTouchingVitals() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(row(ROOM, "ONLINE", NOW));
        when(ingestLogMapper.insertIgnoreDuplicate(GATEWAY, DEVICE, "B1", NOW)).thenReturn(0);

        IngestResult result = service.ingest(GATEWAY, DEVICE, TOKEN,
                new IngestRequest("B1", List.of(frame(1, 0), frame(2, 0))), null);

        assertThat(result.duplicateBatch()).isTrue();
        assertThat(result.acceptedRows()).isZero();
        assertThat(result.duplicateRows()).isEqualTo(2);
        verify(vitalMapper, never()).insertFrames(any());
        verify(ingestLogMapper, never()).updateCounts(any(), any(), anyLong(), anyLong(), anyLong());
        assertThat(auditAction()).isEqualTo("DEVICE_INGEST");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestRejectsInvalidFramesButStoresValidOnesWithQualityFlags() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(row(ROOM, "ONLINE", NOW));
        when(ingestLogMapper.insertIgnoreDuplicate(GATEWAY, DEVICE, "B2", NOW)).thenReturn(1);
        when(vitalMapper.insertFrames(any())).thenReturn(outcome(1, 1, 0));
        IngestFrame suspect = new IngestFrame(2L, NOW, null, null, 76, 98, 120, 80,
                new BigDecimal("35.0"), new BigDecimal("36.7"), 1, List.of("hr:RANGE"), null);
        IngestFrame invalid = new IngestFrame(-1L, NOW, null, null, 76, 98, 120, 80,
                null, null, 0, List.of(), null);

        IngestResult result = service.ingest(GATEWAY, DEVICE, TOKEN,
                new IngestRequest("B2", List.of(frame(1, 0), suspect, invalid)), null);

        assertThat(result.acceptedRows()).isEqualTo(1);
        assertThat(result.rejectedRows()).isEqualTo(1);
        assertThat(result.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reason())
                        .isEqualTo(AcquisitionConstants.REJECT_SEQ_INVALID));
        ArgumentCaptor<List<com.anesop.m2.impl.persistence.entity.VitalInsertRow>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(vitalMapper).insertFrames(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(1).getQuality()).isEqualTo(1);
        assertThat(captor.getValue().get(1).getQualityFlags()).isEqualTo("hr:RANGE");
        assertThat(captor.getValue().get(1).getSource()).isEqualTo(AcquisitionConstants.SOURCE_AUTO);
        verify(registryMapper).markSeen(eq(GATEWAY), eq(DEVICE), eq(NOW), eq(NOW.toEpochMilli()),
                eq("B2"), eq(1L), eq(1L), eq(1L), eq(1L), eq(0L));
        assertThat(auditDetail()).containsEntry("suspectRows", 1L)
                .containsEntry("duplicateRows", 1L);
    }

    @Test
    void ingestCountsRowLevelDuplicatesSeparately() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(row(ROOM, "ONLINE", NOW));
        when(ingestLogMapper.insertIgnoreDuplicate(GATEWAY, DEVICE, "B3", NOW)).thenReturn(1);
        when(vitalMapper.insertFrames(any())).thenReturn(outcome(1, 0, 0));

        IngestResult result = service.ingest(GATEWAY, DEVICE, TOKEN,
                new IngestRequest("B3", List.of(frame(1, 0), frame(2, 0))), null);

        assertThat(result.acceptedRows()).isEqualTo(1);
        assertThat(result.duplicateRows()).isEqualTo(1);
        assertThat(result.rejectedRows()).isZero();
        assertThat(result.duplicateBatch()).isFalse();
    }

    @Test
    void ingestRejectsUnknownDeviceAndInvalidBatch() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(null);
        assertThatThrownBy(() -> service.ingest(GATEWAY, DEVICE, TOKEN,
                new IngestRequest("B4", List.of(frame(1, 0))), null))
                .isInstanceOf(NoSuchElementException.class);

        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(row(ROOM, "ONLINE", NOW));
        assertThatThrownBy(() -> service.ingest(GATEWAY, DEVICE, TOKEN,
                new IngestRequest("bad key with space", List.of(frame(1, 0))), null))
                .isInstanceOf(M2ValidationException.class);
        assertThatThrownBy(() -> service.ingest(GATEWAY, DEVICE, TOKEN,
                new IngestRequest("B5", List.of()), null))
                .isInstanceOf(M2ValidationException.class);
    }

    // --------------------------------------------------------------- 体征查询 --

    @Test
    void queryVitalsPaginatesFramesAndFlattensMetrics() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(row(ROOM, "ONLINE", NOW));
        when(vitalMapper.countVitals(GATEWAY, DEVICE, NOW.minusSeconds(600), NOW, null)).thenReturn(3L);
        VitalRow vital = new VitalRow();
        vital.setSeq(1L);
        vital.setMeasuredAt(NOW.minusSeconds(30));
        vital.setHr(76);
        vital.setTemp(new BigDecimal("36.7"));
        vital.setQuality(0);
        vital.setQualityFlags("hr:RANGE");
        when(vitalMapper.selectVitals(GATEWAY, DEVICE, NOW.minusSeconds(600), NOW, null, 2, 0L))
                .thenReturn(List.of(vital));

        VitalPage page = service.queryVitals(GATEWAY, DEVICE, NOW.minusSeconds(600), NOW, null, 1, 2,
                principal(), null);

        assertThat(page.items()).extracting(item -> item.metric()).containsExactly("HR", "TEMP");
        assertThat(page.items().get(0).value()).isEqualByComparingTo("76");
        assertThat(page.items().get(0).qualityFlags()).containsExactly("hr:RANGE");
        assertThat(page.meta().total()).isEqualTo(3);
        assertThat(page.meta().totalPages()).isEqualTo(2);
        assertThat(auditAction()).isEqualTo("DEVICE_QUERY");
        assertThat(auditDetail()).containsEntry("frames", 3L)
                .doesNotContainKey("patientId");
    }

    @Test
    void queryVitalsValidatesWindowQualityAndPaging() {
        Instant from = NOW.minusSeconds(48 * 3600);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, from, NOW, null, 1, 50,
                principal(), null)).isInstanceOf(M2UnprocessableException.class);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, NOW, NOW, null, 1, 50,
                principal(), null)).isInstanceOf(M2ValidationException.class);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, NOW.minusSeconds(60), NOW, 5, 1, 50,
                principal(), null)).isInstanceOf(M2ValidationException.class);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, NOW.minusSeconds(60), NOW, null, 0, 50,
                principal(), null)).isInstanceOf(M2ValidationException.class);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, NOW.minusSeconds(60), NOW, null, 1, 201,
                principal(), null)).isInstanceOf(M2ValidationException.class);
    }

    @Test
    void queryVitalsReturnsNotFoundForUnregisteredDevice() {
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(null);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, NOW.minusSeconds(60), NOW, null, 1, 50,
                principal(), null)).isInstanceOf(NoSuchElementException.class);
    }

    // ----------------------------------------------------------- 数据范围（B-1）--

    @Test
    void listAndVitalsFilterByVisibleRoomsAndFailClosed() {
        UUID otherRoom = UUID.fromString("80000000-0000-4000-8000-000000000002");
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of()))));
        when(schedulingService.listRooms(principal(), null, null)).thenReturn(roomList(ROOM));
        DeviceRegistryRow visible = row(ROOM, AcquisitionConstants.STATUS_ONLINE, NOW);
        DeviceRegistryRow outOfScope = row(otherRoom, AcquisitionConstants.STATUS_ONLINE, NOW);
        when(registryMapper.selectDevices(null, null))
                .thenReturn(List.of(visible, outOfScope));

        DeviceList list = service.listDevices(null, null, null, principal(), null);
        assertThat(list.items()).singleElement()
                .satisfies(item -> assertThat(item.roomId()).isEqualTo(ROOM));

        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(outOfScope);
        assertThatThrownBy(() -> service.queryVitals(GATEWAY, DEVICE, NOW.minusSeconds(60), NOW,
                null, 1, 50, principal(), null)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.listDevices(null, null, otherRoom, principal(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, otherRoom,
                        AcquisitionConstants.TYPE_MONITOR, null), principal(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, null,
                        AcquisitionConstants.TYPE_MONITOR, null), principal(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listDevicesFailsClosedWhenScopeDenied() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service.listDevices(null, null, null, principal(), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, ROOM,
                        AcquisitionConstants.TYPE_MONITOR, null), principal(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void registerKeepsExistingMaintenanceWhenStatusOmitted() {
        DeviceRegistryRow existing = row(ROOM, AcquisitionConstants.STATUS_MAINTENANCE, NOW);
        UUID newRoom = UUID.fromString("80000000-0000-4000-8000-000000000002");
        DeviceRegistryRow rebound = row(newRoom, AcquisitionConstants.STATUS_MAINTENANCE, NOW);
        rebound.setVersion(2L);
        when(registryMapper.selectByKey(GATEWAY, DEVICE)).thenReturn(existing, rebound);
        when(registryMapper.updateBinding(eq(GATEWAY), eq(DEVICE), eq(1L), eq(newRoom),
                eq(AcquisitionConstants.TYPE_MONITOR), eq(AcquisitionConstants.STATUS_MAINTENANCE),
                any(), eq(ACTOR))).thenReturn(1);

        RegisterOutcome outcome = service.register(
                new DeviceRegisterRequest(GATEWAY, DEVICE, newRoom,
                        AcquisitionConstants.TYPE_MONITOR, null), principal(), null);

        assertThat(outcome.device().status()).isEqualTo(AcquisitionConstants.STATUS_MAINTENANCE);
        assertThat(outcome.created()).isFalse();
    }

    // --------------------------------------------------------------- 健康摘要 --

    @Test
    void healthAggregatesGatewayDevicesAndIngestStats() {
        DeviceRegistryRow online = row(ROOM, "ONLINE", NOW.minusSeconds(10));
        DeviceRegistryRow stale = row(UUID.randomUUID(), "ONLINE", NOW.minusSeconds(600));
        when(registryMapper.selectDevices(null, null)).thenReturn(List.of(online, stale));
        GatewayIngestStatsRow stats = new GatewayIngestStatsRow();
        stats.setGatewayId(GATEWAY);
        stats.setBatches(4L);
        stats.setAcceptedRows(40L);
        stats.setDuplicateRows(2L);
        stats.setRejectedRows(0L);
        stats.setLastReceivedAt(NOW.minusSeconds(5));
        when(ingestLogMapper.statsSince(any(), any())).thenReturn(List.of(stats));
        GatewayQualityRow quality = new GatewayQualityRow();
        quality.setGatewayId(GATEWAY);
        quality.setTotalRows(40L);
        quality.setSuspectRows(2L);
        quality.setMissingRows(0L);
        when(vitalMapper.qualitySince(any(), any())).thenReturn(List.of(quality));

        DeviceHealth health = service.health();

        assertThat(health.status()).isEqualTo("HEALTHY");
        assertThat(health.devices().total()).isEqualTo(2);
        assertThat(health.devices().online()).isEqualTo(1);
        assertThat(health.devices().offline()).isEqualTo(1);
        assertThat(health.ingest().batches()).isEqualTo(4);
        assertThat(health.offlineTimeoutSeconds()).isEqualTo(120);
        assertThat(health.gateways()).singleElement().satisfies(gateway -> {
            assertThat(gateway.gatewayId()).isEqualTo(GATEWAY);
            assertThat(gateway.status()).isEqualTo("ONLINE");
            assertThat(gateway.qualityRate()).isEqualTo(0.05d);
        });
    }

    @Test
    void healthReportsNoDataAndDegradedWhenNeverIngested() {
        when(registryMapper.selectDevices(null, null)).thenReturn(List.of());
        assertThat(service.health().status()).isEqualTo("NO_DATA");

        DeviceRegistryRow registered = row(ROOM, "ONLINE", null);
        when(registryMapper.selectDevices(null, null)).thenReturn(List.of(registered));
        DeviceHealth degraded = service.health();
        assertThat(degraded.status()).isEqualTo("DEGRADED");
        assertThat(degraded.gateways()).singleElement()
                .satisfies(gateway -> assertThat(gateway.status()).isEqualTo("NO_INGEST"));
    }

    // ----------------------------------------------------------------- helpers --

    private static VitalInsertOutcome outcome(long inserted, long suspect, long missing) {
        VitalInsertOutcome outcome = new VitalInsertOutcome();
        outcome.setInserted(inserted);
        outcome.setSuspectRows(suspect);
        outcome.setMissingRows(missing);
        return outcome;
    }

    private static OperatingRoomList roomList(UUID... roomIds) {
        List<OperatingRoom> rooms = new ArrayList<>();
        for (UUID roomId : roomIds) {
            OperatingRoom room = new OperatingRoom();
            room.setRoomId(roomId.toString());
            rooms.add(room);
        }
        return new OperatingRoomList(rooms);
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(ACTOR, "synth.admin", "jti-1", NOW.plusSeconds(900), "sid-1");
    }

    private static DeviceRegistryRow row(UUID roomId, String status, Instant lastSeenAt) {
        DeviceRegistryRow row = new DeviceRegistryRow();
        row.setId(UUID.randomUUID());
        row.setGatewayId(GATEWAY);
        row.setDeviceId(DEVICE);
        row.setRoomId(roomId);
        row.setDeviceType(AcquisitionConstants.TYPE_MONITOR);
        row.setStatus(status);
        row.setLastSeenAt(lastSeenAt);
        row.setCreatedAt(NOW.minusSeconds(3600));
        row.setUpdatedAt(NOW.minusSeconds(60));
        row.setVersion(1L);
        return row;
    }

    private static IngestFrame frame(long seq, int quality) {
        return new IngestFrame(seq, NOW.minusSeconds(seq), null, null, 76, 98, 120, 80,
                new BigDecimal("35.0"), new BigDecimal("36.7"), quality, List.of(), "AUTO");
    }

    private String auditAction() {
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditWriter, org.mockito.Mockito.atLeastOnce()).record(action.capture(), anyString(),
                anyString(), eq(true), any(), any(), any(), any());
        return action.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditDetail() {
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, org.mockito.Mockito.atLeastOnce()).record(anyString(), anyString(),
                anyString(), eq(true), detail.capture(), any(), any(), any());
        List<Map<String, Object>> values = new ArrayList<>(detail.getAllValues());
        return values.get(values.size() - 1);
    }
}
