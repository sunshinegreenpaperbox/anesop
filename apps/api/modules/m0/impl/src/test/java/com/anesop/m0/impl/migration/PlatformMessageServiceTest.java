package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.anesop.contracts.platform.model.Message;
import com.anesop.contracts.platform.model.MessagePage;
import com.anesop.contracts.platform.model.MessageType;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m0.api.platform.MessagePublisher;
import com.anesop.m0.api.platform.MessageTemplates;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.persistence.mapper.SysMessageMapper;
import com.anesop.m0.impl.platform.MessageServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 站内消息服务集成测试（M8-M0-04，真实嵌入式 PostgreSQL 16 + Flyway V27）。
 *
 * <p>覆盖：仅本人可见（他人消息 404、不泄露存在性）、未读过滤与未读角标、分页夹紧、
 * 单条已读幂等、全部已读只影响本人、模板编码/标题/参数白名单校验、审计
 * MESSAGE_READ / MESSAGE_READ_ALL。夹具全部为 SYNTHETIC 账号与消息（无 PHI）。
 */
class PlatformMessageServiceTest {

    private static final UUID ALICE = UUID.fromString("e0000000-0000-4000-8000-00000000e001");
    private static final UUID BOB = UUID.fromString("e0000000-0000-4000-8000-00000000e002");
    private static final AuthPrincipal ALICE_PRINCIPAL = new AuthPrincipal(
            ALICE, "synth.msg.alice", "jti-e001", Instant.parse("2026-11-15T12:00:00Z"), "sid-e001");
    private static final AuthPrincipal BOB_PRINCIPAL = new AuthPrincipal(
            BOB, "synth.msg.bob", "jti-e002", Instant.parse("2026-11-15T12:00:00Z"), "sid-e002");
    private static final AuthRequestContext CONTEXT = AuthRequestContext.empty();
    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sqlSessionFactory;

    private SqlSession session;
    private AuditWriter auditWriter;
    private MessageServiceImpl service;

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
        insertUser(ALICE, "synth.msg.alice");
        insertUser(BOB, "synth.msg.bob");
        session = sqlSessionFactory.openSession(true);
        auditWriter = mock(AuditWriter.class);
        service = new MessageServiceImpl(session.getMapper(SysMessageMapper.class), auditWriter,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void publishThenListReturnsOnlyOwnMessages() {
        service.publish(command(ALICE, MessageType.SCHEDULE, "SCHEDULE_CHANGED",
                Map.of("roomNo", "OR-01", "startAt", "2026-11-16T08:30:00+08:00")));
        service.publish(command(BOB, MessageType.SYSTEM, "SYSTEM_BROADCAST", Map.of()));

        MessagePage alicePage = service.list(ALICE_PRINCIPAL, 1, 20, false);

        assertThat(alicePage.getItems()).singleElement().satisfies(message -> {
            assertThat(message.getType()).isEqualTo(MessageType.SCHEDULE);
            assertThat(message.getTemplateCode()).isEqualTo("SCHEDULE_CHANGED");
            assertThat(message.getTitle()).as("标题由服务端模板注册表渲染").isEqualTo("台次时间已调整");
            assertThat(message.getParams()).containsEntry("roomNo", "OR-01");
            assertThat(message.getRead()).isFalse();
            assertThat(message.getReadAt()).isNull();
        });
        assertThat(alicePage.getUnreadCount()).isEqualTo(1);
        assertThat(service.list(BOB_PRINCIPAL, 1, 20, false).getItems()).hasSize(1);
    }

    @Test
    void unreadOnlyFiltersAndUnreadCountIsIndependent() {
        UUID first = service.publish(command(ALICE, MessageType.ALERT, "ALERT_CRITICAL",
                Map.of("count", "2")));
        service.publish(command(ALICE, MessageType.TASK, "TASK_ASSIGNED", Map.of("count", "1")));
        service.markRead(first, ALICE_PRINCIPAL, CONTEXT);

        MessagePage all = service.list(ALICE_PRINCIPAL, 1, 20, false);
        MessagePage unread = service.list(ALICE_PRINCIPAL, 1, 20, true);

        assertThat(all.getItems()).hasSize(2);
        assertThat(all.getUnreadCount()).isEqualTo(1);
        assertThat(unread.getItems()).hasSize(1);
        assertThat(unread.getUnreadCount()).as("未读数不受 unreadOnly 影响").isEqualTo(1);
    }

    @Test
    void listClampsPageSizeAndHandlesEmpty() {
        MessagePage empty = service.list(ALICE_PRINCIPAL, 0, 1000, false);

        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getMeta().getPage()).isEqualTo(1);
        assertThat(empty.getMeta().getSize()).isEqualTo(100);
        assertThat(empty.getMeta().getTotal()).isZero();
        assertThat(empty.getUnreadCount()).isZero();
    }

    @Test
    void markReadIsIdempotentAndAudited() {
        UUID id = service.publish(command(ALICE, MessageType.QC, "QC_REVIEW_DUE", Map.of()));

        Message first = service.markRead(id, ALICE_PRINCIPAL, CONTEXT);
        Message second = service.markRead(id, ALICE_PRINCIPAL, CONTEXT);

        assertThat(first.getRead()).isTrue();
        assertThat(first.getReadAt()).isNotNull();
        assertThat(second.getReadAt()).as("重复标记不改变首次已读时间").isEqualTo(first.getReadAt());
        verify(auditWriter, org.mockito.Mockito.times(2)).record(eq("MESSAGE_READ"), eq("MESSAGE"),
                eq(id.toString()), eq(true), anyMap(), eq(ALICE), eq("synth.msg.alice"), eq(CONTEXT));
    }

    @Test
    void cannotReadAnotherUsersMessage() {
        UUID bobMessage = service.publish(command(BOB, MessageType.SYSTEM, "SYSTEM_BROADCAST",
                Map.of()));

        assertThatThrownBy(() -> service.markRead(bobMessage, ALICE_PRINCIPAL, CONTEXT))
                .as("他人的消息必须 404，不能泄露存在性")
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.markRead(UUID.randomUUID(), ALICE_PRINCIPAL, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void markAllReadOnlyAffectsOwnUnreadAndAuditsCount() {
        service.publish(command(ALICE, MessageType.SYSTEM, "SYSTEM_BROADCAST", Map.of()));
        service.publish(command(ALICE, MessageType.SYSTEM, "SYSTEM_BROADCAST", Map.of()));
        service.publish(command(BOB, MessageType.SYSTEM, "SYSTEM_BROADCAST", Map.of()));

        int updated = service.markAllRead(ALICE_PRINCIPAL, CONTEXT);
        int again = service.markAllRead(ALICE_PRINCIPAL, CONTEXT);

        assertThat(updated).isEqualTo(2);
        assertThat(again).isZero();
        assertThat(service.list(ALICE_PRINCIPAL, 1, 20, true).getItems()).isEmpty();
        assertThat(service.list(BOB_PRINCIPAL, 1, 20, true).getItems())
                .as("不能把他人消息标记为已读")
                .hasSize(1);
        // 每次调用都留痕（幂等调用返回 0 也审计，便于识别重复点击）。
        verify(auditWriter, org.mockito.Mockito.times(2)).record(eq("MESSAGE_READ_ALL"),
                eq("MESSAGE"), eq(ALICE.toString()), eq(true), anyMap(), eq(ALICE),
                eq("synth.msg.alice"), eq(CONTEXT));
    }

    @Test
    void publishRejectsUnregisteredTemplateTypeMismatchAndInvalidParams() {
        assertThatThrownBy(() -> service.publish(command(ALICE, MessageType.SYSTEM, "lower_case",
                Map.of())))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.publish(command(ALICE, MessageType.SYSTEM, "ARBITRARY_TITLE",
                Map.of())))
                .as("未在服务端注册的模板编码必须拒绝（防任意文案）")
                .isInstanceOf(M0ValidationException.class)
                .hasMessageContaining("模板未在服务端注册");
        assertThatThrownBy(() -> service.publish(command(ALICE, MessageType.SYSTEM,
                "SCHEDULE_CHANGED", Map.of("roomNo", "OR-01"))))
                .as("命令类型必须与注册表模板类型一致")
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.publish(command(ALICE, MessageType.SYSTEM, "SYSTEM_BROADCAST",
                Map.of("patientName", "张三"))))
                .as("参数键不在该模板允许范围（含 PHI 风险键）必须拒绝")
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.publish(command(ALICE, MessageType.ALERT, "ALERT_CRITICAL",
                Map.of("count", "x".repeat(201)))))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.publish(command(ALICE, MessageType.ALERT, "ALERT_CRITICAL",
                Map.of("count", "1\n2"))))
                .as("参数值不得含控制字符")
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.publish(new MessagePublisher.MessageCommand(
                null, MessageType.SYSTEM, "SYSTEM_BROADCAST", Map.of())))
                .isInstanceOf(M0ValidationException.class);
        assertThatThrownBy(() -> service.publish(new MessagePublisher.MessageCommand(
                ALICE, null, "SYSTEM_BROADCAST", Map.of())))
                .isInstanceOf(M0ValidationException.class);
    }

    @Test
    void registeredTemplatesCannotRenderArbitraryTitleOrPhiKey() {
        service.publish(command(ALICE, MessageType.ALERT, "MATERIAL_LOW_STOCK",
                Map.of("deptName", "麻醉科（合成）", "count", "2")));

        Message message = service.list(ALICE_PRINCIPAL, 1, 20, false).getItems().get(0);
        assertThat(message.getTitle()).isEqualTo("耗材库存不足");
        assertThat(message.getParams()).containsOnlyKeys("deptName", "count");
        assertThat(MessageTemplates.all()).containsKeys("SYSTEM_BROADCAST", "SCHEDULE_CHANGED",
                "SCHEDULE_STOPPED", "ALERT_CRITICAL", "QC_REVIEW_DUE", "TASK_ASSIGNED",
                "MATERIAL_LOW_STOCK");
        assertThat(MessageTemplates.find("UNKNOWN")).isEmpty();
    }

    @Test
    void malformedStoredParamsDegradeToEmptyWithoutLeaking() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO m0_platform.sys_message
                    (id, recipient_user_id, message_type, template_code, title, params, created_at)
                VALUES (?::uuid, ?::uuid, 'SYSTEM', 'SYSTEM_BROADCAST', '系统维护通知',
                        '{"deptName": {"nested": 1}}'::jsonb, now())
                """, id.toString(), ALICE.toString());

        Message message = service.list(ALICE_PRINCIPAL, 1, 20, false).getItems().get(0);
        assertThat(message.getParams()).isEmpty();
        assertThat(message.getTitle()).isEqualTo("系统维护通知");
    }

    @Test
    void paramsAreDefensivelyCopiedAndSerialized() {
        MessagePublisher.MessageCommand command = command(ALICE, MessageType.SYSTEM,
                "SYSTEM_BROADCAST", Map.of("deptName", "麻醉科（合成）"));
        service.publish(command);

        Message message = service.list(ALICE_PRINCIPAL, 1, 20, false).getItems().get(0);
        assertThat(message.getParams()).containsExactly(Map.entry("deptName", "麻醉科（合成）"));
    }

    private static MessagePublisher.MessageCommand command(UUID recipient, MessageType type,
            String templateCode, Map<String, String> params) {
        return new MessagePublisher.MessageCommand(recipient, type, templateCode, params);
    }

    private static void insertUser(UUID id, String username) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, 'SYNTHETIC-ONLY-NOT-A-REAL-HASH', '合成消息用户', 1, false, 0, 0, false)
                """, id.toString(), username);
    }
}
