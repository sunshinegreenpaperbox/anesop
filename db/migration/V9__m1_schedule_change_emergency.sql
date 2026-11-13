-- =============================================================================
-- ANESOP 数据库迁移 V9：M1 停改台、急诊加台与通知 outbox（M2-M2-02）
--
-- 设计依据：
--   方案书 §2.4.2（急诊加台、停台/改台、变更留痕、通知）、§2.3.2（急诊优先）
--   方案书 §3.4.2（排程/台次实体）、§3.9.2（RBAC 与数据权限）、§3.9.6（审计留痕）
--   契约：packages/contracts/openapi/scheduling.yaml 0.5.0
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M2-02（停改台与急诊加台）
--
-- 内容（expand 段：只新增表/索引/权限点，不改写既有表与既有数据）：
--   1) m1_sched.schedule_change_request：台次变更申请/审批留痕（**完全 append-only**：
--      应用层只 INSERT，审批结果在写入时即为 APPROVED/REJECTED/PENDING；MVP 由持
--      m1:schedule:approve 的操作人直接批准，同一事务内写 APPROVED 行，不做 UPDATE。
--      PENDING/REJECTED 预留给后续双人审批）。数据库触发器拒绝 UPDATE/DELETE/TRUNCATE
--      （SQLSTATE 55000，复用 V7 状态历史触发器风格），防止事后改写审批链。
--      change_type=STOP/MODIFY/EMERGENCY；preempt_schedule_id 记录急诊抢占关系。
--   2) m1_sched.notification_outbox：通知发件箱（模拟通知端口，EXT：真实短信/企业微信
--      待院方）。payload 为 jsonb，只允许标识/原因码/状态，禁止患者姓名、住院号、证件号、
--      手机号、诊断等 PHI 明文；数据库 CHECK 以禁用键兜底（应用层另有白名单构造）。
--   3) 索引/约束：按台次+时间、申请、状态、抢占关系、发件箱待发送队列建索引；
--      ck_change_request_approval 保证审批状态与审批人/时间一致；
--      ck_notification_outbox_no_phi 拒绝含 PHI 键的 payload；ck_notification_outbox_no_phi_value
--      在 JSON 值层面拒绝 15 位以上连续数字（身份证/住院号等长数字串，UUID 不会误伤）。
--   4) 权限点 m1:schedule:approve（三段式编码）：仅授予 SYS_ADMIN、SYS_OR_MANAGER
--      （术中停台与急诊加台/抢占审批；V8 的 m1:schedule:read|write 授权不变）。
--
-- 安全与临床约定：
--   - 无 PHI：两张新表都不存患者姓名/住院号/证件号/手机号；变更记录只存申请/台次标识。
--   - 停台不物理删除：schedule 置 CANCELLED + reason，change_request 保留审批链。
--   - 急诊优先于择期：抢占择期台次时写两条变更记录（择期 STOP + 急诊 EMERGENCY），
--     便于从任一台次追溯抢占关系；被抢占台次保留原计划时段供统计。
--   - 通知为模拟端口（SIMULATED）：只写 outbox 并记录日志，不接真实通道；真实短信/
--     企业微信为 EXT，接入前不得把 PHI 写入 payload。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.3）：
--   1) 应用回滚：下线停台/急诊加台/变更历史路由（M1 排程读写其余能力不受影响）；
--   2) 库层前向修复：DROP TABLE m1_sched.notification_outbox；
--      DROP TABLE m1_sched.schedule_change_request；
--      DELETE m1:schedule:approve 的授权与权限点；
--   3) 回滚前导出两张表（审批留痕与待发通知），在演练库验证 DROP 顺序；
--      生产执行须人签字；已产生的审批记录不得丢失，须先归档到离线审计库。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. schedule_change_request 台次变更申请/审批留痕
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.schedule_change_request (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    schedule_id         uuid         NOT NULL,
    application_id      uuid         NOT NULL,
    change_type         varchar(16)  NOT NULL,
    reason_code         varchar(32)  NOT NULL,
    reason              varchar(500),
    requested_by        uuid,
    requested_at        timestamptz  NOT NULL DEFAULT now(),
    approved_by         uuid,
    approved_at         timestamptz,
    status              varchar(16)  NOT NULL DEFAULT 'PENDING',
    preempt_schedule_id uuid,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    created_by          uuid,
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    updated_by          uuid,
    version             bigint       NOT NULL DEFAULT 0,
    deleted             boolean      NOT NULL DEFAULT false,
    CONSTRAINT pk_schedule_change_request PRIMARY KEY (id),
    CONSTRAINT fk_change_request_schedule FOREIGN KEY (schedule_id)
        REFERENCES m1_sched.schedule (id) ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_application FOREIGN KEY (application_id)
        REFERENCES m1_sched.surgery_application (id) ON DELETE RESTRICT,
    CONSTRAINT fk_change_request_preempt FOREIGN KEY (preempt_schedule_id)
        REFERENCES m1_sched.schedule (id) ON DELETE RESTRICT,
    CONSTRAINT ck_change_request_type
        CHECK (change_type IN ('STOP', 'MODIFY', 'EMERGENCY')),
    CONSTRAINT ck_change_request_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_change_request_reason_code
        CHECK (reason_code ~ '^[A-Z_]{2,32}$'),
    -- 审批状态与审批人/时间必须一致：PENDING 无审批人；APPROVED/REJECTED 必须有审批人与时间。
    CONSTRAINT ck_change_request_approval CHECK (
        (status = 'PENDING' AND approved_by IS NULL AND approved_at IS NULL)
        OR (status = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
        OR (status = 'REJECTED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    -- 抢占关系不得指向自身。
    CONSTRAINT ck_change_request_preempt_not_self
        CHECK (preempt_schedule_id IS NULL OR preempt_schedule_id <> schedule_id)
);

-- 变更历史按台次倒序查询（GET /schedules/{id}/changes）。
CREATE INDEX idx_change_request_schedule
    ON m1_sched.schedule_change_request (schedule_id, requested_at DESC);
CREATE INDEX idx_change_request_application_id
    ON m1_sched.schedule_change_request (application_id);
CREATE INDEX idx_change_request_status
    ON m1_sched.schedule_change_request (status);
CREATE INDEX idx_change_request_change_type
    ON m1_sched.schedule_change_request (change_type);
CREATE INDEX idx_change_request_preempt
    ON m1_sched.schedule_change_request (preempt_schedule_id);
CREATE INDEX idx_change_request_requested_at
    ON m1_sched.schedule_change_request (requested_at);
CREATE INDEX idx_change_request_deleted
    ON m1_sched.schedule_change_request (deleted);

COMMENT ON TABLE m1_sched.schedule_change_request IS
    '台次变更申请/审批留痕：STOP/MODIFY/EMERGENCY，记录原因码、审批人与抢占关系；只增不改';
COMMENT ON COLUMN m1_sched.schedule_change_request.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.schedule_change_request.schedule_id IS
    '台次 UUID，FK → m1_sched.schedule.id；被抢占的择期台次也各有一条 STOP 记录';
COMMENT ON COLUMN m1_sched.schedule_change_request.application_id IS
    '手术申请 UUID，FK → m1_sched.surgery_application.id；由台次冗余便于按申请追溯';
COMMENT ON COLUMN m1_sched.schedule_change_request.change_type IS
    '变更类型：STOP=停台，MODIFY=改台，EMERGENCY=急诊加台/抢占';
COMMENT ON COLUMN m1_sched.schedule_change_request.reason_code IS
    '原因码（白名单，2–32 位大写字母/下划线）：PATIENT_CONDITION/EMERGENCY_PREEMPT 等';
COMMENT ON COLUMN m1_sched.schedule_change_request.reason IS
    '原因说明（中性文本，≤500 字符）；不存患者姓名/住院号/诊断等 PHI';
COMMENT ON COLUMN m1_sched.schedule_change_request.requested_by IS '申请/执行人用户 UUID';
COMMENT ON COLUMN m1_sched.schedule_change_request.requested_at IS '申请/执行时间（timestamptz）';
COMMENT ON COLUMN m1_sched.schedule_change_request.approved_by IS
    '审批人用户 UUID；PENDING 为 NULL；MVP 为持 m1:schedule:approve 的执行人';
COMMENT ON COLUMN m1_sched.schedule_change_request.approved_at IS
    '审批时间（timestamptz）；PENDING 为 NULL';
COMMENT ON COLUMN m1_sched.schedule_change_request.status IS
    '审批状态：PENDING/APPROVED/REJECTED；写入即定稿（append-only），MVP 直接生效记 APPROVED';
COMMENT ON COLUMN m1_sched.schedule_change_request.preempt_schedule_id IS
    '抢占关系中的另一台次 UUID，FK → m1_sched.schedule.id；无抢占为 NULL';
COMMENT ON COLUMN m1_sched.schedule_change_request.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m1_sched.schedule_change_request.created_by IS '创建人用户 UUID';
COMMENT ON COLUMN m1_sched.schedule_change_request.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m1_sched.schedule_change_request.updated_by IS '最后更新人用户 UUID';
COMMENT ON COLUMN m1_sched.schedule_change_request.version IS '乐观锁版本号（创建为 1）';
COMMENT ON COLUMN m1_sched.schedule_change_request.deleted IS
    '软删除标记：审批留痕不物理删除，恒为 false';

-- 变更留痕完全 append-only：任何 UPDATE / DELETE / TRUNCATE 在数据库层被拒绝（B-2）。
-- 审批结果在同一事务内以新行（status=APPROVED）写入，不做状态回写，避免留痕被篡改。
CREATE OR REPLACE FUNCTION m1_sched.fn_schedule_change_request_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm1_sched.schedule_change_request is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '审批留痕只能 INSERT；更正/驳回请写新的变更记录，不得改写或删除历史';
END;
$$;

COMMENT ON FUNCTION m1_sched.fn_schedule_change_request_append_only() IS
    '台次变更/审批留痕 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_schedule_change_request_no_update
    BEFORE UPDATE ON m1_sched.schedule_change_request
    FOR EACH ROW EXECUTE FUNCTION m1_sched.fn_schedule_change_request_append_only();

CREATE TRIGGER trg_schedule_change_request_no_delete
    BEFORE DELETE ON m1_sched.schedule_change_request
    FOR EACH ROW EXECUTE FUNCTION m1_sched.fn_schedule_change_request_append_only();

CREATE TRIGGER trg_schedule_change_request_no_truncate
    BEFORE TRUNCATE ON m1_sched.schedule_change_request
    FOR EACH STATEMENT EXECUTE FUNCTION m1_sched.fn_schedule_change_request_append_only();

-- -----------------------------------------------------------------------------
-- 2. notification_outbox 通知发件箱（模拟通知端口）
-- -----------------------------------------------------------------------------
CREATE TABLE m1_sched.notification_outbox (
    id              uuid         NOT NULL DEFAULT gen_random_uuid(),
    event_type      varchar(64)  NOT NULL,
    payload         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status          varchar(16)  NOT NULL DEFAULT 'PENDING',
    attempts        integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_error      varchar(500),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    CONSTRAINT pk_notification_outbox PRIMARY KEY (id),
    CONSTRAINT ck_notification_outbox_event_type
        CHECK (event_type ~ '^[A-Z_]{2,64}$'),
    CONSTRAINT ck_notification_outbox_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notification_outbox_sent
        CHECK (status <> 'SENT' OR sent_at IS NOT NULL),
    CONSTRAINT ck_notification_outbox_payload_object
        CHECK (jsonb_typeof(payload) = 'object'),
    -- 无 PHI 兜底：payload 禁止出现患者标识/临床文本键（应用层只写标识与原因码）。
    CONSTRAINT ck_notification_outbox_no_phi CHECK (NOT (payload ?| ARRAY[
        'patientName', 'patientNo', 'hisPatientNo', 'inpatientNo', 'inpatientNoMasked',
        'name', 'idCard', 'idCardMasked', 'phone', 'phoneMasked', 'diagnosis',
        'preopDiagnosis', 'birthDate', 'ward', 'bedNo'])),
    -- 值级无 PHI 兜底：拒绝 15 位以上连续数字（身份证 15/18 位、长住院号等）。
    -- 载荷中的 UUID 每段最多 12 位数字，正常标识不会误伤；手机号 11 位无法与 UUID 段区分，
    -- 由应用层白名单构造 + 键级 CHECK 兜底。
    CONSTRAINT ck_notification_outbox_no_phi_value
        CHECK (payload::text !~ '[0-9]{15}')
);

-- 派发队列：按状态 + 下次重试时间轮询（每次 drain 1 条）。
CREATE INDEX idx_notification_outbox_pending
    ON m1_sched.notification_outbox (status, next_attempt_at);
CREATE INDEX idx_notification_outbox_created_at
    ON m1_sched.notification_outbox (created_at);
CREATE INDEX idx_notification_outbox_event_type
    ON m1_sched.notification_outbox (event_type);

COMMENT ON TABLE m1_sched.notification_outbox IS
    '通知发件箱（模拟端口，SIMULATED）：只写标识/原因码，真实短信/企业微信为 EXT 待院方接入';
COMMENT ON COLUMN m1_sched.notification_outbox.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m1_sched.notification_outbox.event_type IS
    '事件类型：SCHEDULE_STOPPED/SCHEDULE_MODIFIED/EMERGENCY_SCHEDULED/SCHEDULE_PREEMPTED';
COMMENT ON COLUMN m1_sched.notification_outbox.payload IS
    '通知载荷（jsonb）：只允许台次/申请/术间/原因码等标识，禁止 PHI 明文（见 CHECK 约束）';
COMMENT ON COLUMN m1_sched.notification_outbox.status IS
    '发送状态：PENDING=待发，SENT=已发，FAILED=重试耗尽';
COMMENT ON COLUMN m1_sched.notification_outbox.attempts IS '已尝试发送次数（0 起）';
COMMENT ON COLUMN m1_sched.notification_outbox.next_attempt_at IS '下次可发送时间（退避重试用）';
COMMENT ON COLUMN m1_sched.notification_outbox.last_error IS
    '最近一次失败原因（固定文案，不含 PHI/SQL 实参）';
COMMENT ON COLUMN m1_sched.notification_outbox.created_at IS '入队时间（timestamptz）';
COMMENT ON COLUMN m1_sched.notification_outbox.sent_at IS '发送成功时间；未发送为 NULL';

-- -----------------------------------------------------------------------------
-- 3. 权限点 m1:schedule:approve
--    仅授予 SYS_ADMIN、SYS_OR_MANAGER：术中停台、急诊加台/抢占审批（最小权限）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-000000000029', 'm1:schedule:approve', 'M1 停台与急诊加台审批', 'API',
     '/api/v1/scheduling/emergency-cases', 'POST', NULL, 120);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000029'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000029');
