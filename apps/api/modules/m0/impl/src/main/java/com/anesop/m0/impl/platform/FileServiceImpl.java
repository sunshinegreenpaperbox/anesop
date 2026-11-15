package com.anesop.m0.impl.platform;

import com.anesop.contracts.platform.model.FileBusinessType;
import com.anesop.contracts.platform.model.FileMeta;
import com.anesop.contracts.platform.model.FilePage;
import com.anesop.contracts.platform.model.FileScanStatus;
import com.anesop.contracts.platform.model.FileUploadRequest;
import com.anesop.contracts.platform.model.PageMeta;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.FileBusinessVisibilityPort;
import com.anesop.m0.api.platform.FileDownload;
import com.anesop.m0.api.platform.FileService;
import com.anesop.m0.api.platform.FileStorage;
import com.anesop.m0.api.platform.M0UnprocessableException;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.config.FileProperties;
import com.anesop.m0.impl.persistence.entity.SysFile;
import com.anesop.m0.impl.persistence.mapper.SysFileMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件服务实现（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>校验与安全要点：
 * <ul>
 *   <li>类型白名单：application/pdf、text/csv、text/plain、image/png、image/jpeg，
 *       其他类型 422（**禁止**临床影像 DICOM/视频、身份证明与含患者标识的表格/截图）；</li>
 *   <li>大小：解码后 ≤20 MiB 且必须与 {@code sizeBytes} 一致，否则 422；Base64 非法 400；</li>
 *   <li>文件名：客户端原始名只做本地语法校验（路径分隔符/控制字符/长度），
 *       <b>不落库、不入审计</b>；展示名由服务端生成 {@code file-<uuid>.<ext>}（评审 B-2）；</li>
 *   <li>内容 SHA-256 服务端计算并按内容寻址落盘（同内容只存一份），元数据每次上传独立；</li>
 *   <li>{@code businessId} 只接受 UUID 或大写业务号（字母/数字/短横线），拒绝自由文本；</li>
 *   <li>下载可见性：上传者 / SYS_ADMIN / 业务模块只读权限，且业务对象必须落在调用方数据范围内
 *       （{@link FileBusinessVisibilityPort}）；缺模块权限 403，业务对象不存在/超范围 404；
 *       感染状态拒绝下载；不存在或已删除统一 404；</li>
 *   <li>审计：FILE_UPLOAD / FILE_DOWNLOAD，detail 只含业务类型/大小/摘要前 12 位，
 *       <b>不含 fileName、businessId 或文件内容</b>。</li>
 * </ul>
 */
@Service
public class FileServiceImpl implements FileService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ACTION_UPLOAD = "FILE_UPLOAD";
    private static final String ACTION_DOWNLOAD = "FILE_DOWNLOAD";
    private static final String RESOURCE_TYPE = "FILE";
    private static final String ROLE_ADMIN = "SYS_ADMIN";
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_BUSINESS_ID_LENGTH = 64;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int AUDIT_SHA_PREFIX = 12;
    /** UUID（8-4-4-4-12 十六进制）或大写业务号（如 CASE-2026-0001），拒绝自由文本与 PHI。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern BUSINESS_NO_PATTERN = Pattern.compile(
            "^[A-Z][A-Z0-9]{1,15}(?:-[A-Z0-9]{1,16}){0,3}$");
    /**
     * 20 MiB 原文的标准 Base64 最大字符数（4 * ceil(n/3)），用于在解码前拒绝超限请求，
     * 避免为超大内容分配内存。
     */
    private static final int MAX_BASE64_CHARS = ((int) ((FileProperties.CONTRACT_MAX_SIZE_BYTES + 2) / 3)) * 4;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "text/csv", "text/plain", "image/png", "image/jpeg");

    /** 业务类型 → 需要的模块只读权限（无映射表示仅上传者/管理员可见）。 */
    private static final Map<String, String> BUSINESS_READ_PERMISSIONS = Map.of(
            "REPORT", "m4:report:read",
            "SCHEDULE", "m1:schedule:read",
            "RECORD", "m2:record:read");

    private final SysFileMapper fileMapper;
    private final FileStorage storage;
    private final AuditWriter auditWriter;
    private final FileProperties properties;
    private final FileBusinessVisibilityPort visibilityPort;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 单例注入的 Mapper/存储适配器/配置，生命周期由容器管理，服务不对外暴露引用")
    public FileServiceImpl(SysFileMapper fileMapper, FileStorage storage, AuditWriter auditWriter,
            FileProperties properties, FileBusinessVisibilityPort visibilityPort, Clock authClock) {
        this.fileMapper = fileMapper;
        this.storage = storage;
        this.auditWriter = auditWriter;
        this.properties = properties;
        this.visibilityPort = visibilityPort;
        this.clock = authClock;
    }

    @Override
    @Transactional
    public FileMeta upload(FileUploadRequest request, AuthPrincipal caller, AuthRequestContext context) {
        if (caller == null || caller.userId() == null) {
            throw new NoSuchElementException("文件不存在或不可见");
        }
        if (request == null) {
            throw new M0ValidationException("file", "上传请求不能为空");
        }
        validateFileName(request.getFileName());
        String contentType = validateContentType(request.getContentType());
        String businessType = validateBusinessType(request.getBusinessType());
        String businessId = validateBusinessId(request.getBusinessId());
        byte[] content = decodeContent(request.getContentBase64());
        if (content.length > properties.getMaxSizeBytes()) {
            throw new M0UnprocessableException("sizeBytes", "文件大小超出上限（20MB）");
        }
        if (request.getSizeBytes() == null) {
            throw new M0ValidationException("sizeBytes", "文件大小不能为空");
        }
        if (request.getSizeBytes() != content.length) {
            throw new M0UnprocessableException("sizeBytes", "声明大小与实际内容不一致");
        }

        String sha256 = sha256(content);
        String storageKey = storage.store(sha256, content);

        SysFile row = new SysFile();
        row.setId(UUID.randomUUID());
        row.setStorageKey(storageKey);
        row.setContentType(contentType);
        row.setSizeBytes((long) content.length);
        row.setSha256(sha256);
        row.setBusinessType(businessType);
        row.setBusinessId(businessId);
        row.setUploaderId(caller.userId());
        row.setScanStatus(FileScanStatus.NOT_SCANNED.getValue());
        row.setDeleted(false);
        row.setCreatedAt(Instant.now(clock));
        fileMapper.insert(row);

        auditWriter.record(ACTION_UPLOAD, RESOURCE_TYPE, row.getId().toString(), true,
                auditDetail(row), caller.userId(), caller.username(), context);
        return toModel(row);
    }

    @Override
    @Transactional
    public FileDownload download(UUID fileId, AuthPrincipal caller, Set<String> permissions,
            Set<String> roles, AuthRequestContext context) {
        SysFile row = requireVisible(fileId, caller, permissions, roles);
        if (FileScanStatus.INFECTED.getValue().equals(row.getScanStatus())) {
            throw new AccessDeniedException("文件未通过安全检查，禁止下载");
        }
        byte[] content = storage.load(row.getStorageKey());

        auditWriter.record(ACTION_DOWNLOAD, RESOURCE_TYPE, row.getId().toString(), true,
                auditDetail(row), caller.userId(), caller.username(), context);
        return new FileDownload(toModel(row), content);
    }

    @Override
    @Transactional(readOnly = true)
    public FileMeta meta(UUID fileId, AuthPrincipal caller, Set<String> permissions, Set<String> roles) {
        return toModel(requireVisible(fileId, caller, permissions, roles));
    }

    @Override
    @Transactional(readOnly = true)
    public FilePage listMine(AuthPrincipal caller, int page, int size) {
        if (caller == null || caller.userId() == null) {
            throw new NoSuchElementException("文件不存在或不可见");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        long offset = (long) (safePage - 1) * safeSize;

        Long counted = fileMapper.selectCount(new QueryWrapper<SysFile>()
                .eq("uploader_id", caller.userId())
                .eq("deleted", false));
        long total = counted == null ? 0 : counted;

        List<FileMeta> items = new ArrayList<>();
        if (total > offset) {
            List<SysFile> rows = fileMapper.selectList(new QueryWrapper<SysFile>()
                    .eq("uploader_id", caller.userId())
                    .eq("deleted", false)
                    .orderByDesc("created_at")
                    .orderByDesc("id")
                    .last("LIMIT " + safeSize + " OFFSET " + offset));
            for (SysFile row : rows) {
                items.add(toModel(row));
            }
        }

        PageMeta meta = new PageMeta();
        meta.setPage(safePage);
        meta.setSize(safeSize);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0
                : (int) Math.min(Integer.MAX_VALUE, (total + safeSize - 1) / safeSize));

        FilePage result = new FilePage();
        result.setItems(items);
        result.setMeta(meta);
        return result;
    }

    private SysFile requireVisible(UUID fileId, AuthPrincipal caller, Set<String> permissions,
            Set<String> roles) {
        if (fileId == null) {
            throw new M0ValidationException("fileId", "文件标识不能为空");
        }
        SysFile row = fileMapper.selectOne(new QueryWrapper<SysFile>()
                .eq("id", fileId)
                .eq("deleted", false)
                .last("LIMIT 1"));
        if (row == null || Boolean.TRUE.equals(row.getDeleted())) {
            throw new NoSuchElementException("文件不存在或不可见");
        }
        if (!hasFunctionalAccess(row, caller, permissions, roles)) {
            // 缺模块只读权限：403（与既有契约一致）。
            throw new AccessDeniedException("文件不可见或无权下载");
        }
        if (!isBusinessVisible(row, caller, roles)) {
            // 业务对象不存在/超出数据范围：404，防止通过附件探测其他科室业务对象。
            throw new NoSuchElementException("文件不存在或不可见");
        }
        return row;
    }

    /** 功能权限：上传者 / SYS_ADMIN / TEMPLATE 公开 / 业务类型对应模块只读权限。 */
    private static boolean hasFunctionalAccess(SysFile row, AuthPrincipal caller,
            Set<String> permissions, Set<String> roles) {
        if (caller == null || caller.userId() == null) {
            return false;
        }
        if (caller.userId().equals(row.getUploaderId())) {
            return true;
        }
        if (roles != null && roles.contains(ROLE_ADMIN)) {
            return true;
        }
        String businessType = row.getBusinessType();
        if ("TEMPLATE".equals(businessType)) {
            // 模板公开给持 m0:file:read 者：Web 层与 Service 层双重校验，防内部调用绕过。
            return permissions != null && permissions.contains("m0:file:read");
        }
        String required = BUSINESS_READ_PERMISSIONS.get(businessType);
        return required != null && permissions != null && permissions.contains(required);
    }

    /**
     * 数据范围：上传者/管理员/TEMPLATE 直接可见；SCHEDULE/RECORD/REPORT 交业务端口判定。
     * 端口缺失、业务对象标识缺失或判定异常一律 fail-closed。
     */
    private boolean isBusinessVisible(SysFile row, AuthPrincipal caller, Set<String> roles) {
        if (caller == null || caller.userId() == null) {
            return false;
        }
        if (caller.userId().equals(row.getUploaderId())) {
            return true;
        }
        if (roles != null && roles.contains(ROLE_ADMIN)) {
            // 平台管理员按既有口径可见全部文件（下载仍写审计）。
            return true;
        }
        String businessType = row.getBusinessType();
        if ("TEMPLATE".equals(businessType)) {
            return true;
        }
        if (!BUSINESS_READ_PERMISSIONS.containsKey(businessType)) {
            // ATTACHMENT 仅上传者/管理员（已在上层判定），未知类型 fail-closed。
            return false;
        }
        if (row.getBusinessId() == null || row.getBusinessId().isBlank()) {
            return false;
        }
        if (visibilityPort == null) {
            return false;
        }
        try {
            return visibilityPort.isVisible(caller, businessType, row.getBusinessId());
        } catch (RuntimeException e) {
            // 适配器/业务模块异常不得放行（fail-closed）；不记录 businessId 或异常消息。
            return false;
        }
    }

    private static void validateFileName(String fileName) {
        String value = fileName == null ? "" : fileName.trim();
        if (value.isEmpty() || value.length() > MAX_FILE_NAME_LENGTH) {
            throw new M0ValidationException("fileName", "文件名长度必须为 1–255 字符");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f || ch == '/' || ch == '\\' || ch == '"') {
                throw new M0ValidationException("fileName",
                        "文件名不得包含路径分隔符、引号或控制字符");
            }
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new M0ValidationException("fileName", "文件名不合法");
        }
    }

    private static String validateContentType(FileUploadRequest.ContentTypeEnum contentType) {
        if (contentType == null) {
            // schema 必填缺失 → 400（与契约一致）；枚举外的值由 Jackson/Bean Validation 先行 400。
            throw new M0ValidationException("contentType", "文件类型不能为空");
        }
        if (!ALLOWED_CONTENT_TYPES.contains(contentType.getValue())) {
            throw new M0UnprocessableException("contentType",
                    "文件类型不在允许范围（报表/模板/附件：PDF、CSV、TXT、PNG、JPEG）");
        }
        return contentType.getValue();
    }

    private static String validateBusinessType(FileBusinessType businessType) {
        if (businessType == null) {
            throw new M0ValidationException("businessType", "业务类型不能为空");
        }
        return businessType.getValue();
    }

    private static String validateBusinessId(String businessId) {
        if (businessId == null) {
            return null;
        }
        String value = businessId.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_BUSINESS_ID_LENGTH) {
            throw new M0ValidationException("businessId", "业务关联标识长度不能超过 64 字符");
        }
        if (!UUID_PATTERN.matcher(value).matches() && !BUSINESS_NO_PATTERN.matcher(value).matches()) {
            // 拒绝自由文本/中文/空格：避免把患者姓名、住院号等写进业务关联标识与索引。
            throw new M0ValidationException("businessId",
                    "业务关联标识只允许 UUID 或大写业务号（字母/数字/短横线）");
        }
        return value;
    }

    private byte[] decodeContent(String contentBase64) {
        String value = contentBase64 == null ? "" : contentBase64.trim();
        if (value.isEmpty()) {
            throw new M0ValidationException("contentBase64", "文件内容不能为空");
        }
        if (value.length() > MAX_BASE64_CHARS) {
            throw new M0UnprocessableException("sizeBytes", "文件大小超出上限（20MB）");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0) {
                throw new M0ValidationException("contentBase64", "文件内容不能为空");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            // 不回显 Base64 原文或解码位置，只给固定文案。
            throw new M0ValidationException("contentBase64", "内容不是合法的 Base64 编码");
        }
    }

    private static Map<String, Object> auditDetail(SysFile row) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("businessType", row.getBusinessType());
        detail.put("sizeBytes", row.getSizeBytes());
        detail.put("sha256Prefix", row.getSha256() == null ? null
                : row.getSha256().substring(0, AUDIT_SHA_PREFIX));
        // 硬约束（评审 B-2）：不写 fileName / businessId / 文件内容。
        return detail;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256；缺失属运行环境损坏，直接失败而不是静默降级。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 服务端生成的展示名：{@code file-<uuid>.<ext>}，不含客户端原始文件名。 */
    private static String generatedFileName(UUID fileId, String contentType) {
        String extension = switch (contentType == null ? "" : contentType) {
            case "application/pdf" -> "pdf";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> "bin";
        };
        return "file-" + fileId + "." + extension;
    }

    private static FileMeta toModel(SysFile row) {
        FileMeta model = new FileMeta();
        model.setFileId(row.getId());
        model.setFileName(generatedFileName(row.getId(), row.getContentType()));
        model.setContentType(FileMeta.ContentTypeEnum.fromValue(row.getContentType()));
        model.setSizeBytes(row.getSizeBytes() == null ? 0 : Math.toIntExact(row.getSizeBytes()));
        model.setSha256(row.getSha256());
        model.setBusinessType(FileBusinessType.fromValue(row.getBusinessType()));
        model.setBusinessId(row.getBusinessId());
        model.setUploaderId(row.getUploaderId());
        model.setScanStatus(FileScanStatus.fromValue(row.getScanStatus()));
        model.setCreatedAt(offset(row.getCreatedAt()));
        return model;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZONE);
    }
}
