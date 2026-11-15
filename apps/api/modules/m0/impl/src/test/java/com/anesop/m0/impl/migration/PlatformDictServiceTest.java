package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.anesop.contracts.platform.model.CreateDictItemRequest;
import com.anesop.contracts.platform.model.DictItem;
import com.anesop.contracts.platform.model.DictItemList;
import com.anesop.contracts.platform.model.DictTypeList;
import com.anesop.contracts.platform.model.UpdateDictItemRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.M0ConflictException;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.persistence.mapper.SysDictItemMapper;
import com.anesop.m0.impl.persistence.mapper.SysDictMapper;
import com.anesop.m0.impl.platform.DictServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * 字典服务集成测试（M8-M0-04，真实嵌入式 PostgreSQL 16 + 真实 Flyway V27）。
 *
 * <p>覆盖：类型列表启用项计数、字典项启用过滤与排序、includeDisabled 仅管理员、
 * 编码唯一（409 语义）、编码不可变、逻辑停用幂等、非管理员 403、类型/项不存在 404、
 * 字段校验 400、审计动作 DICT_ITEM_CREATE/UPDATE/DISABLE。
 * 数据全部为 SYNTHETIC（复用 V2 平台基线字典，测试内新增项随用例重置）。
 */
class PlatformDictServiceTest {

    private static final AuthPrincipal ADMIN = new AuthPrincipal(
            UUID.fromString("e0000000-0000-4000-8000-00000000d001"), "synth.dict.admin",
            "jti-d001", Instant.parse("2026-11-15T12:00:00Z"), "sid-d001");
    private static final AuthPrincipal DOCTOR = new AuthPrincipal(
            UUID.fromString("e0000000-0000-4000-8000-00000000d002"), "synth.dict.doctor",
            "jti-d002", Instant.parse("2026-11-15T12:00:00Z"), "sid-d002");
    private static final Set<String> ADMIN_ROLES = Set.of("SYS_ADMIN");
    private static final Set<String> DOCTOR_ROLES = Set.of("SYS_ANESTHESIOLOGIST");
    private static final AuthRequestContext CONTEXT = AuthRequestContext.empty();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession session;
    private AuditWriter auditWriter;
    private DictServiceImpl service;

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
        session = sqlSessionFactory.openSession(true);
        auditWriter = mock(AuditWriter.class);
        service = new DictServiceImpl(session.getMapper(SysDictMapper.class),
                session.getMapper(SysDictItemMapper.class), auditWriter,
                Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void listTypesReturnsEnabledTypesWithEnabledItemCount() {
        DictTypeList types = service.listTypes();

        assertThat(types.getItems()).extracting("type")
                .contains("SEX", "ASA", "SURGERY_URGENCY", "OR_ROOM_STATUS", "CASE_STATUS", "ROLE_TYPE");
        assertThat(types.getItems()).filteredOn(item -> "ASA".equals(item.getType()))
                .singleElement()
                .satisfies(item -> assertThat(item.getItemCount()).isEqualTo(6));
    }

    @Test
    void listItemsReturnsEnabledItemsOrderedBySort() {
        DictItemList items = service.listItems("ASA", false, false);

        assertThat(items.getType()).isEqualTo("ASA");
        assertThat(items.getItems()).extracting("code")
                .containsExactly("I", "II", "III", "IV", "V", "VI");
        assertThat(items.getItems()).allSatisfy(item -> assertThat(item.getEnabled()).isTrue());
    }

    @Test
    void disabledItemsAreHiddenByDefaultAndVisibleOnlyToManager() {
        UUID itemId = UUID.fromString("d0000000-0000-4000-8000-000000000009");
        service.disableItem("ASA", itemId, ADMIN, ADMIN_ROLES, CONTEXT);

        assertThat(service.listItems("ASA", false, true).getItems()).extracting("code")
                .doesNotContain("VI");
        assertThat(service.listItems("ASA", true, true).getItems()).extracting("code")
                .contains("VI");

        assertThatThrownBy(() -> service.listItems("ASA", true, false))
                .as("普通用户请求停用项必须 403（不暴露停用语义）")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unknownTypeIsNotFound() {
        assertThatThrownBy(() -> service.listItems("NO_SUCH_TYPE", false, false))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createItemPersistsAndAudits() {
        DictItem created = service.createItem("ASA", createRequest("VII", "ASA VII 级", 7, true),
                ADMIN, ADMIN_ROLES, CONTEXT);

        assertThat(created.getCode()).isEqualTo("VII");
        assertThat(created.getName()).isEqualTo("ASA VII 级");
        assertThat(created.getEnabled()).isTrue();
        assertThat(created.getItemId()).isNotNull();
        verify(auditWriter).record(eq("DICT_ITEM_CREATE"), eq("DICT_ITEM"),
                eq(created.getItemId().toString()), eq(true), anyMap(), eq(ADMIN.userId()),
                eq(ADMIN.username()), eq(CONTEXT));
    }

    @Test
    void duplicateCodeIsConflict() {
        assertThatThrownBy(() -> service.createItem("ASA", createRequest("I", "重复编码", 1, true),
                ADMIN, ADMIN_ROLES, CONTEXT))
                .as("同类型内编码重复必须 409 语义")
                .isInstanceOf(M0ConflictException.class);
    }

    @Test
    void nonAdminCannotCreateOrUpdateOrDisable() {
        CreateDictItemRequest request = createRequest("VIII", "ASA VIII 级", 8, true);
        UUID itemId = UUID.fromString("d0000000-0000-4000-8000-000000000001");

        assertThatThrownBy(() -> service.createItem("ASA", request, DOCTOR, DOCTOR_ROLES, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.updateItem("ASA", itemId, updateRequest("改名", 1, true),
                DOCTOR, DOCTOR_ROLES, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.disableItem("ASA", itemId, DOCTOR, DOCTOR_ROLES, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateChangesFieldsButKeepsCode() {
        UUID itemId = UUID.fromString("d0000000-0000-4000-8000-000000000004");
        DictItem updated = service.updateItem("ASA", itemId,
                updateRequest("ASA I 级（修订）", 11, false), ADMIN, ADMIN_ROLES, CONTEXT);

        assertThat(updated.getCode()).isEqualTo("I");
        assertThat(updated.getName()).isEqualTo("ASA I 级（修订）");
        assertThat(updated.getSort()).isEqualTo(11);
        assertThat(updated.getEnabled()).isFalse();

        verify(auditWriter).record(eq("DICT_ITEM_UPDATE"), eq("DICT_ITEM"), eq(itemId.toString()),
                eq(true), anyMap(), eq(ADMIN.userId()), eq(ADMIN.username()), eq(CONTEXT));
    }

    @Test
    void updateWithUnknownItemIsNotFound() {
        assertThatThrownBy(() -> service.updateItem("ASA", UUID.randomUUID(),
                updateRequest("不存在", 1, true), ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateWithItemFromAnotherTypeIsNotFound() {
        // SEX 的字典项不属于 ASA 类型：必须 404，不能跨类型改项。
        UUID sexItemId = UUID.fromString("d0000000-0000-4000-8000-000000000001");
        assertThatThrownBy(() -> service.updateItem("ASA", sexItemId,
                updateRequest("跨类型", 1, true), ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void invalidFieldValuesAreRejected() {
        assertThatThrownBy(() -> service.createItem("ASA", createRequest("bad code!", "名称", 1, true),
                ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(M0ValidationException.class)
                .hasMessageContaining("编码");
        assertThatThrownBy(() -> service.createItem("ASA", createRequest("VIII", " ", 1, true),
                ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.createItem("ASA", createRequest("VIII", "名称", 10000, true),
                ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.createItem(" ", createRequest("VIII", "名称", 1, true),
                ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
    }

    @Test
    void remarkTooLongIsRejected() {
        CreateDictItemRequest request = createRequest("VIII", "名称", 1, true);
        request.setRemark("x".repeat(256));

        assertThatThrownBy(() -> service.createItem("ASA", request, ADMIN, ADMIN_ROLES, CONTEXT))
                .isInstanceOf(M0ValidationException.class);
    }

    @Test
    void disableIsIdempotentAndAudited() {
        UUID itemId = UUID.fromString("d0000000-0000-4000-8000-000000000005");

        service.disableItem("ASA", itemId, ADMIN, ADMIN_ROLES, CONTEXT);
        service.disableItem("ASA", itemId, ADMIN, ADMIN_ROLES, CONTEXT);

        Integer status = jdbc.queryForObject(
                "SELECT status FROM m0_platform.sys_dict_item WHERE id = ?::uuid",
                Integer.class, itemId.toString());
        assertThat(status).isZero();

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditWriter, times(2)).record(action.capture(), eq("DICT_ITEM"), eq(itemId.toString()),
                anyBoolean(), anyMap(), eq(ADMIN.userId()), eq(ADMIN.username()), eq(CONTEXT));
        assertThat(action.getAllValues()).containsOnly("DICT_ITEM_DISABLE");
    }

    @Test
    void disabledTypeIsNotListedAndItemsAreNotFound() {
        jdbc.update("UPDATE m0_platform.sys_dict_type SET status = 0 WHERE code = 'SEX'");

        assertThat(service.listTypes().getItems()).extracting("type").doesNotContain("SEX");
        assertThatThrownBy(() -> service.listItems("SEX", false, false))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static CreateDictItemRequest createRequest(String code, String name, int sort,
            boolean enabled) {
        CreateDictItemRequest request = new CreateDictItemRequest();
        request.setCode(code);
        request.setName(name);
        request.setSort(sort);
        request.setEnabled(enabled);
        return request;
    }

    private static UpdateDictItemRequest updateRequest(String name, int sort, boolean enabled) {
        UpdateDictItemRequest request = new UpdateDictItemRequest();
        request.setName(name);
        request.setSort(sort);
        request.setEnabled(enabled);
        return request;
    }
}
