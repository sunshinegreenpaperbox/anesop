package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.anesop.contracts.platform.model.FileBusinessType;
import com.anesop.contracts.platform.model.FileMeta;
import com.anesop.contracts.platform.model.FilePage;
import com.anesop.contracts.platform.model.FileUploadRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.FileBusinessVisibilityPort;
import com.anesop.m0.api.platform.FileDownload;
import com.anesop.m0.api.platform.M0UnprocessableException;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.config.FileProperties;
import com.anesop.m0.impl.persistence.mapper.SysFileMapper;
import com.anesop.m0.impl.platform.FileServiceImpl;
import com.anesop.m0.impl.platform.LocalFileStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * 文件服务集成测试（M8-M0-04，真实 PostgreSQL 16 + Flyway V27 + 本地 FS 适配器临时目录）。
 *
 * <p>覆盖：上传（类型/大小/Base64/文件名/businessId 模式校验）、SHA-256 内容寻址去重、
 * 服务端生成文件名（不持久化客户端原名）、元数据不含存储路径、下载可见性
 * （上传者 / SYS_ADMIN / 业务模块只读权限 / 业务对象数据范围 / TEMPLATE 公开 / ATTACHMENT
 * 仅上传者）、跨数据范围 404、缺模块权限 403、不存在 404、感染状态拒绝、我的文件分页、
 * 库层类型/大小白名单（纵深防御）、审计 FILE_UPLOAD/FILE_DOWNLOAD 且 detail 不含
 * fileName/businessId。文件内容为 SYNTHETIC 文本，不含任何患者数据。
 */
class PlatformFileServiceTest {

    private static final UUID UPLOADER = UUID.fromString("e0000000-0000-4000-8000-00000000f001");
    private static final UUID COLLEAGUE = UUID.fromString("e0000000-0000-4000-8000-00000000f002");
    private static final UUID ADMIN = UUID.fromString("e0000000-0000-4000-8000-00000000f003");
    private static final UUID SCHEDULE_ID = UUID.fromString("80000000-0000-4000-8000-00000000f101");
    private static final UUID OTHER_SCHEDULE_ID =
            UUID.fromString("80000000-0000-4000-8000-00000000f102");
    private static final AuthPrincipal UPLOADER_PRINCIPAL = principal(UPLOADER, "synth.file.uploader");
    private static final AuthPrincipal COLLEAGUE_PRINCIPAL = principal(COLLEAGUE, "synth.file.colleague");
    private static final AuthPrincipal ADMIN_PRINCIPAL = principal(ADMIN, "synth.file.admin");
    private static final AuthRequestContext CONTEXT = AuthRequestContext.empty();
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final byte[] CSV =
            "operating_room,shift\nOR-01,DAY\nOR-02,NIGHT\n".getBytes(StandardCharsets.UTF_8);

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sqlSessionFactory;

    @TempDir
    Path tempDir;

    private SqlSession session;
    private AuditWriter auditWriter;
    private FileProperties properties;
    private FileServiceImpl service;
    private Set<String> visibleBusinessIds;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dataSource = PostgresSupport.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        sqlSessionFactory = MybatisTestSupport.sqlSessionFactory(dataSource);
    }

    @BeforeEach
    void migrateAndCreateService() throws Exception {
        MigrationTestSupport.resetDatabase(dataSource);
        assertThat(MigrationTestSupport.flyway(dataSource).migrate().success).isTrue();
        insertUser(UPLOADER, "synth.file.uploader");
        insertUser(COLLEAGUE, "synth.file.colleague");
        insertUser(ADMIN, "synth.file.admin");
        session = sqlSessionFactory.openSession(true);
        auditWriter = mock(AuditWriter.class);
        properties = new FileProperties();
        visibleBusinessIds = new HashSet<>();
        FileBusinessVisibilityPort port = (caller, businessType, businessId) ->
                visibleBusinessIds.contains(businessId);
        service = new FileServiceImpl(session.getMapper(SysFileMapper.class),
                new LocalFileStorage(tempDir.resolve("files")), auditWriter, properties, port,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void uploadStoresContentAndReturnsServerGeneratedNameWithoutStorageKey() throws IOException {
        FileMeta meta = service.upload(uploadRequest("科室排班模板（合成）.csv", FileBusinessType.TEMPLATE,
                CSV, null), UPLOADER_PRINCIPAL, CONTEXT);

        assertThat(meta.getFileName())
                .as("客户端原始文件名不得回显/持久化，服务端生成 file-<id>.<ext>")
                .isEqualTo("file-" + meta.getFileId() + ".csv")
                .doesNotContain("科室排班模板");
        assertThat(meta.getContentType().getValue()).isEqualTo("text/csv");
        assertThat(meta.getSizeBytes()).isEqualTo(CSV.length);
        assertThat(meta.getSha256()).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(meta.getScanStatus().getValue()).isEqualTo("NOT_SCANNED");
        assertThat(meta.getUploaderId()).isEqualTo(UPLOADER);

        assertThat(service.meta(meta.getFileId(), UPLOADER_PRINCIPAL, Set.of("m0:file:read"),
                Set.of()).getFileId()).isEqualTo(meta.getFileId());
        verify(auditWriter).record(eq("FILE_UPLOAD"), eq("FILE"), eq(meta.getFileId().toString()),
                eq(true), anyMap(), eq(UPLOADER), eq("synth.file.uploader"), eq(CONTEXT));
        assertThat(countStoredFiles()).isEqualTo(1);
    }

    @Test
    void uploadAuditDetailOmitsFileNameBusinessIdAndContent() {
        FileMeta meta = service.upload(uploadRequest("含姓名的排班表.csv", FileBusinessType.ATTACHMENT,
                CSV, "CASE-2026-0001"), UPLOADER_PRINCIPAL, CONTEXT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("FILE_UPLOAD"), eq("FILE"), eq(meta.getFileId().toString()),
                eq(true), detail.capture(), eq(UPLOADER), eq("synth.file.uploader"), eq(CONTEXT));

        assertThat(detail.getValue())
                .as("审计 detail 只允许 businessType/sizeBytes/sha256Prefix")
                .containsOnlyKeys("businessType", "sizeBytes", "sha256Prefix");
        assertThat(detail.getValue().values().toString())
                .as("审计 detail 不得含 fileName、businessId 或文件内容")
                .doesNotContain("含姓名的排班表")
                .doesNotContain("CASE-2026-0001")
                .doesNotContain("operating_room");
        assertThat(detail.getValue().get("sha256Prefix")).asString().hasSize(12);
    }

    @Test
    void duplicateContentDedupesOnDiskButKeepsSeparateMetadata() {
        FileMeta first = service.upload(uploadRequest("a.csv", FileBusinessType.TEMPLATE, CSV, null),
                UPLOADER_PRINCIPAL, CONTEXT);
        FileMeta second = service.upload(uploadRequest("b.csv", FileBusinessType.TEMPLATE, CSV, null),
                UPLOADER_PRINCIPAL, CONTEXT);

        assertThat(second.getFileId()).isNotEqualTo(first.getFileId());
        assertThat(second.getSha256()).isEqualTo(first.getSha256());
        assertThat(countStoredFiles()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM m0_platform.sys_file", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void invalidBase64FileNameAndBusinessIdAreRejected() {
        FileUploadRequest badBase64 = uploadRequest("a.csv", FileBusinessType.TEMPLATE, CSV, null);
        badBase64.setContentBase64("not-base64!!");

        assertThatThrownBy(() -> service.upload(badBase64, UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class)
                .hasMessageContaining("Base64");
        assertThatThrownBy(() -> service.upload(uploadRequest("../evil.csv", FileBusinessType.TEMPLATE,
                CSV, null), UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.upload(uploadRequest("a.csv", FileBusinessType.TEMPLATE,
                new byte[0], null), UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.upload(uploadRequest("a.csv", null, CSV, null),
                UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
        FileUploadRequest noType = uploadRequest("a.csv", FileBusinessType.TEMPLATE, CSV, null);
        noType.setContentType(null);
        assertThatThrownBy(() -> service.upload(noType, UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class)
                .hasMessageContaining("不能为空");
        // 自由文本/中文/空格不得进入业务关联标识（评审 B-2）。
        assertThatThrownBy(() -> service.upload(uploadRequest("a.csv", FileBusinessType.ATTACHMENT,
                CSV, "患者 张三 住院号 12345"), UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class)
                .hasMessageContaining("UUID 或大写业务号");
        assertThatThrownBy(() -> service.upload(uploadRequest("a.csv", FileBusinessType.ATTACHMENT,
                CSV, "case-1"), UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.upload(uploadRequest("a.csv", FileBusinessType.ATTACHMENT,
                CSV, "A".repeat(65)), UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0ValidationException.class)
                .hasMessageContaining("64");
    }

    @Test
    void sizeMismatchAndOversizeAreBusinessRejections() throws IOException {
        FileUploadRequest mismatch = uploadRequest("a.csv", FileBusinessType.TEMPLATE, CSV, null);
        mismatch.setSizeBytes(CSV.length + 1);

        assertThatThrownBy(() -> service.upload(mismatch, UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0UnprocessableException.class)
                .hasMessageContaining("不一致");

        // 只允许收紧：把上限设为 8 字节后，12 字节内容必须 422（等价于生产 20 MiB 上限）。
        properties.setMaxSizeBytes(8);
        assertThatThrownBy(() -> service.upload(uploadRequest("b.csv", FileBusinessType.TEMPLATE,
                CSV, null), UPLOADER_PRINCIPAL, CONTEXT))
                .isInstanceOf(M0UnprocessableException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void maxSizeConfigurationCannotExceedContractLimit() {
        FileProperties configured = new FileProperties();
        configured.setMaxSizeBytes(FileProperties.CONTRACT_MAX_SIZE_BYTES + 1);
        assertThat(configured.getMaxSizeBytes()).isEqualTo(FileProperties.CONTRACT_MAX_SIZE_BYTES);

        configured.setMaxSizeBytes(0);
        assertThat(configured.getMaxSizeBytes()).isEqualTo(FileProperties.CONTRACT_MAX_SIZE_BYTES);
    }

    @Test
    void attachmentIsVisibleOnlyToUploaderAndAdmin() {
        FileMeta attachment = service.upload(uploadRequest("附件.txt", FileBusinessType.ATTACHMENT,
                "合成附件内容".getBytes(StandardCharsets.UTF_8), "CASE-1"), UPLOADER_PRINCIPAL, CONTEXT);

        assertThatThrownBy(() -> service.download(attachment.getFileId(), COLLEAGUE_PRINCIPAL,
                Set.of("m0:file:read"), Set.of("SYS_ANESTHESIOLOGIST"), CONTEXT))
                .as("非上传者、非管理员不得下载 ATTACHMENT")
                .isInstanceOf(AccessDeniedException.class);

        FileDownload adminDownload = service.download(attachment.getFileId(), ADMIN_PRINCIPAL,
                Set.of("m0:file:read"), Set.of("SYS_ADMIN"), CONTEXT);
        assertThat(new String(adminDownload.content(), StandardCharsets.UTF_8))
                .isEqualTo("合成附件内容");
    }

    @Test
    void scheduleAttachmentRequiresModulePermissionAndBusinessDataScope() {
        FileMeta schedule = service.upload(uploadRequest("排程附件.csv", FileBusinessType.SCHEDULE,
                CSV, SCHEDULE_ID.toString()), UPLOADER_PRINCIPAL, CONTEXT);
        visibleBusinessIds.add(SCHEDULE_ID.toString());

        assertThatThrownBy(() -> service.download(schedule.getFileId(), COLLEAGUE_PRINCIPAL,
                Set.of("m0:file:read"), Set.of("SYS_SURGEON"), CONTEXT))
                .as("缺 m1:schedule:read → 403")
                .isInstanceOf(AccessDeniedException.class);

        assertThat(new String(service.download(schedule.getFileId(), COLLEAGUE_PRINCIPAL,
                Set.of("m0:file:read", "m1:schedule:read"), Set.of("SYS_SURGEON"), CONTEXT).content(),
                StandardCharsets.UTF_8))
                .as("同数据范围 + 模块权限 → 可下载")
                .isEqualTo(new String(CSV, StandardCharsets.UTF_8));

        FileMeta outOfScope = service.upload(uploadRequest("外科普.csv", FileBusinessType.SCHEDULE,
                CSV, OTHER_SCHEDULE_ID.toString()), UPLOADER_PRINCIPAL, CONTEXT);
        assertThatThrownBy(() -> service.download(outOfScope.getFileId(), COLLEAGUE_PRINCIPAL,
                Set.of("m0:file:read", "m1:schedule:read"), Set.of("SYS_SURGEON"), CONTEXT))
                .as("有模块权限但业务对象超出数据范围 → 404（防存在性泄露）")
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void reportAttachmentIsFailClosedToUploaderAndAdmin() {
        FileMeta report = service.upload(uploadRequest("报表.csv", FileBusinessType.REPORT, CSV, null),
                UPLOADER_PRINCIPAL, CONTEXT);

        assertThatThrownBy(() -> service.download(report.getFileId(), COLLEAGUE_PRINCIPAL,
                Set.of("m0:file:read", "m4:report:read"), Set.of("SYS_SURGEON"), CONTEXT))
                .as("REPORT 无对象标识 → fail-closed 404")
                .isInstanceOf(NoSuchElementException.class);
        assertThat(new String(service.download(report.getFileId(), ADMIN_PRINCIPAL,
                Set.of("m0:file:read"), Set.of("SYS_ADMIN"), CONTEXT).content(),
                StandardCharsets.UTF_8)).isEqualTo(new String(CSV, StandardCharsets.UTF_8));
    }

    @Test
    void templateIsPublicToFileReaders() {
        FileMeta template = service.upload(uploadRequest("模板.txt", FileBusinessType.TEMPLATE,
                "模板内容".getBytes(StandardCharsets.UTF_8), null), UPLOADER_PRINCIPAL, CONTEXT);

        assertThat(service.download(template.getFileId(), COLLEAGUE_PRINCIPAL,
                Set.of("m0:file:read"), Set.of("SYS_SURGEON"), CONTEXT).content())
                .as("TEMPLATE 对持 m0:file:read 者公开")
                .isEqualTo("模板内容".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void missingFileIsNotFoundAndDownloadIsAuditedWithoutFileNameOrBusinessId() {
        FileMeta meta = service.upload(uploadRequest("a.csv", FileBusinessType.ATTACHMENT, CSV, "CASE-1"),
                UPLOADER_PRINCIPAL, CONTEXT);
        service.download(meta.getFileId(), UPLOADER_PRINCIPAL, Set.of("m0:file:read"),
                Set.of(), CONTEXT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("FILE_DOWNLOAD"), eq("FILE"), eq(meta.getFileId().toString()),
                eq(true), detail.capture(), eq(UPLOADER), eq("synth.file.uploader"), eq(CONTEXT));
        assertThat(detail.getValue()).containsOnlyKeys("businessType", "sizeBytes", "sha256Prefix");

        assertThatThrownBy(() -> service.meta(UUID.randomUUID(), UPLOADER_PRINCIPAL,
                Set.of("m0:file:read"), Set.of()))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.meta(null, UPLOADER_PRINCIPAL,
                Set.of("m0:file:read"), Set.of()))
                .isInstanceOf(M0ValidationException.class);
    }

    @Test
    void infectedFileIsDenied() {
        FileMeta meta = service.upload(uploadRequest("a.csv", FileBusinessType.TEMPLATE, CSV, null),
                UPLOADER_PRINCIPAL, CONTEXT);
        jdbc.update("UPDATE m0_platform.sys_file SET scan_status = 'INFECTED' WHERE id = ?::uuid",
                meta.getFileId().toString());

        assertThatThrownBy(() -> service.download(meta.getFileId(), UPLOADER_PRINCIPAL,
                Set.of("m0:file:read"), Set.of(), CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listMineReturnsOnlyOwnFilesWithPaging() {
        FileMeta first = service.upload(uploadRequest("a.csv", FileBusinessType.TEMPLATE, CSV, null),
                UPLOADER_PRINCIPAL, CONTEXT);
        service.upload(uploadRequest("b.csv", FileBusinessType.TEMPLATE,
                "b".getBytes(StandardCharsets.UTF_8), null), UPLOADER_PRINCIPAL, CONTEXT);
        service.upload(uploadRequest("c.csv", FileBusinessType.TEMPLATE,
                "c".getBytes(StandardCharsets.UTF_8), null), COLLEAGUE_PRINCIPAL, CONTEXT);

        FilePage mine = service.listMine(UPLOADER_PRINCIPAL, 1, 2);
        assertThat(mine.getItems()).hasSize(2);
        assertThat(mine.getMeta().getTotal()).isEqualTo(2);
        assertThat(mine.getMeta().getTotalPages()).isEqualTo(1);
        assertThat(mine.getItems()).extracting(FileMeta::getUploaderId).containsOnly(UPLOADER);

        FilePage clamped = service.listMine(UPLOADER_PRINCIPAL, 0, 1000);
        assertThat(clamped.getMeta().getPage()).isEqualTo(1);
        assertThat(clamped.getMeta().getSize()).isEqualTo(100);

        assertThat(service.listMine(COLLEAGUE_PRINCIPAL, 1, 20).getItems())
                .extracting(FileMeta::getFileId)
                .doesNotContain(first.getFileId());
        assertThatThrownBy(() -> service.listMine(null, 1, 20))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void databaseRejectsTypeAndSizeOutsideWhitelist() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO m0_platform.sys_file
                    (storage_key, content_type, size_bytes, sha256, business_type, uploader_id)
                VALUES ('aa/bb/x.bin', 'application/dicom', 10,
                        repeat('a', 64), 'ATTACHMENT', ?::uuid)
                """, UPLOADER.toString()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .as("库层必须拒绝 DICOM 等非白名单类型（PHI 禁令纵深防御）");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO m0_platform.sys_file
                    (storage_key, content_type, size_bytes, sha256, business_type, uploader_id)
                VALUES ('aa/bb/y.bin', 'text/csv', 20971521,
                        repeat('b', 64), 'ATTACHMENT', ?::uuid)
                """, UPLOADER.toString()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .as("库层必须拒绝超过 20 MiB 的记录");
    }

    private long countStoredFiles() {
        Path root = tempDir.resolve("files");
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException("统计存储文件失败", e);
        }
    }

    private static FileUploadRequest uploadRequest(String fileName, FileBusinessType businessType,
            byte[] content, String businessId) {
        FileUploadRequest request = new FileUploadRequest();
        request.setFileName(fileName);
        request.setContentType(FileUploadRequest.ContentTypeEnum.TEXT_CSV);
        request.setSizeBytes(content.length);
        request.setContentBase64(Base64.getEncoder().encodeToString(content));
        request.setBusinessType(businessType);
        request.setBusinessId(businessId);
        return request;
    }

    private static AuthPrincipal principal(UUID id, String username) {
        return new AuthPrincipal(id, username, "jti-" + id, Instant.parse("2026-11-15T12:00:00Z"),
                "sid-" + id);
    }

    private static void insertUser(UUID id, String username) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, 'SYNTHETIC-ONLY-NOT-A-REAL-HASH', '合成文件用户', 1, false, 0, 0, false)
                """, id.toString(), username);
    }
}
