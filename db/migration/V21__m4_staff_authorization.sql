-- =============================================================================
-- ANESOP 数据库迁移 V21：M4 人员档案、分级授权、排班与培训（M4-M13-01 / E-M13）
-- 目标库：PostgreSQL 16；schema：m4_staff
--
-- 设计依据：
--   方案书 §2.4.4（M3 人员管理：人员档案、分级授权、排班、培训与继续教育；
--     关键规则“无授权不得执行对应麻醉方式或术式，排程与记录两处校验；证书到期前 30 天
--     提醒”）、§3.4.1（“人员与资质”限界上下文，核心不变量“未授权不得执行对应麻醉方式”）、
--     §3.4.2（人员 1:N 授权、排班）、§3.4.3（审计字段/乐观锁/软删除约定）、
--     §3.9.2（RBAC 与数据权限）、§3.9.3（脱敏）
--   契约：packages/contracts/openapi/staff.yaml 0.1.0
--   任务卡：docs/ai/tasks/M4-backlog.md M4-M13-01
--   ADR：docs/adr/0019-分级授权与排班口径.md
--
-- 内容（expand 段：只新增 schema/表/索引/约束/触发器/视图/权限点，不改写 V1–V20）：
--   1) m4_staff.staff_profile      人员档案：工号唯一（lower+btrim）、userId 唯一（可空）、
--                                  科室/职称/职级/专长(jsonb 数组)/在职状态 + 乐观锁 version。
--   2) m4_staff.staff_qualification 执业资质：类型/证书号/签发机构/有效期/状态；
--                                  同一人员同一证书号唯一（提交列表为最新快照）。
--   3) m4_staff.staff_authorization 分级授权（**append-only 历史**）：级别 R1–R5、术式范围
--                                  (jsonb 数组)、年龄段、ASA 上限、有效期、审批人；
--                                  同一人员 version 唯一，每次授予/撤销写入新版本。
--                                  VIEW staff_authorization_current 提供“当前版本”读取。
--   4) m4_staff.staff_schedule      排班：日期 + 班次(DAY/NIGHT/ON_CALL) + 术间/科室；
--                                  **唯一冲突约束**：同一人员同日期同班次、同一术间同日期
--                                  同班次（仅 PLANNED 占用；取消后释放名额）。
--   5) m4_staff.staff_training      培训：课程编码/名称、完成/到期日期、证书号；
--                                  同一人员同一课程编码唯一。
--   6) 权限点 m4:staff:read、m4:staff:write、m4:schedule:manage、m4:training:manage
--      （三段式编码），按最小集授予既有角色（不新增角色，避免自助扩权）：
--        - SYS_ADMIN（信息科运维）：四项全授；
--        - SYS_OR_MANAGER（手术室管理员）：read + schedule:manage（排班职责）；
--        - SYS_ANESTHESIOLOGIST（麻醉医师）：read + write（资质/授权复核）+ training:manage；
--        未授予 SYS_NURSE_ANESTHETIST/SYS_SURGEON，越权返回 403（最小权限）。
--
-- 分级授权与排班口径（可配置、待确认）：
--   - **R1–R5 的业务含义与“级别 → 术式范围/年龄段/ASA”默认映射【待确认】**：本迁移不内置
--     映射（不臆造临床授权规则），授权范围以 staff_authorization 的显式字段为权威；
--     院方确认授权目录后，可开启服务端配置
--     `anesop.m4.staff.authorization.level-policies` 叠加“级别上限”校验（超限 422 /
--     校验返回 LEVEL_POLICY_EXCEEDED），无需改表或契约。
--   - **ASA 上限**：ASA1–ASA5（ASA VI 脑死亡器官捐献不纳入本域，实际分级由麻醉医师判定，
--     系统只做授权范围判定，不替代临床决策 AGENTS H-06）。
--   - **术式范围**：`*` 表示全部；其余为院内术式类别/术式编码。术式字典归属 M1（术间与
--     排程）/M0 字典，本域只保存编码，不做字典外键（避免跨模块强耦合）。
--   - **班次定义【待确认】**：方案书要求“日常班/值班/备班”，映射为 DAY（白班）/NIGHT
--     （夜班）/ON_CALL（备班）三值；三班制、节假日班与换班审批流待院方确认后扩展枚举。
--   - **术间同班次冲突**：本迁移按“同一术间同一日期同一班次只安排一名人员”建模（任务卡
--     口径）；如实际需要“一术间同时多名人员（麻醉医师 + 护士）”，需院方确认后新增迁移
--     放宽唯一索引并改为“同角色占用”语义。
--   - **排班变更审批**：本期提供变更（PUT）与取消（POST cancel）留痕与审计，审批流（换班
--     申请/科主任审批）未实现，登记为待确认项（见 ADR-0019）。
--
-- 脱敏与数据范围：
--   - 本域只保存**在职人员**档案数据，不含患者任何标识；审计 detail 不含姓名、证书号原文
--     与联系方式；姓名只用于本域业务展示。
--   - 数据范围复用 m0（ALL/SITE/DEPT/SELF）：ALL 全院、SITE 院区子树、DEPT 授权科室、
--     SELF 仅本人档案/排班/培训；ROOM/无范围 fail-closed 403（服务层实现，不新增权限模型）。
--
-- 迁移纪律：本脚本合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.16）：
--   1) 应用回滚：下线 /v1/staff* 端点与前端“人员档案/分级授权/排班/培训”菜单；M1 排程与
--      M5 术中的授权校验调用方恢复为“不做资质校验”的旧行为（授权表保留不影响旧代码）。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 授权历史归档，顺序不可颠倒）：
--      DROP TRIGGER IF EXISTS trg_staff_authorization_no_update ON m4_staff.staff_authorization;
--      DROP TRIGGER IF EXISTS trg_staff_authorization_no_delete ON m4_staff.staff_authorization;
--      DROP TRIGGER IF EXISTS trg_staff_authorization_no_truncate ON m4_staff.staff_authorization;
--      DROP TRIGGER IF EXISTS trg_staff_profile_guard ON m4_staff.staff_profile;
--      DROP TRIGGER IF EXISTS trg_staff_schedule_guard ON m4_staff.staff_schedule;
--      DROP FUNCTION IF EXISTS m4_staff.fn_reject_mutation();
--      DROP FUNCTION IF EXISTS m4_staff.fn_reject_truncate();
--      DROP FUNCTION IF EXISTS m4_staff.fn_staff_profile_guard();
--      DROP FUNCTION IF EXISTS m4_staff.fn_staff_schedule_guard();
--      DROP VIEW IF EXISTS m4_staff.staff_authorization_current;
--      DROP TABLE m4_staff.staff_training;
--      DROP TABLE m4_staff.staff_schedule;
--      DROP TABLE m4_staff.staff_authorization;
--      DROP TABLE m4_staff.staff_qualification;
--      DROP TABLE m4_staff.staff_profile;
--      DROP SCHEMA IF EXISTS m4_staff;
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000081',
--                                'b0000000-0000-4000-8000-000000000082',
--                                'b0000000-0000-4000-8000-000000000083',
--                                'b0000000-0000-4000-8000-000000000084');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m4:staff:read', 'm4:staff:write', 'm4:schedule:manage',
--                       'm4:training:manage');
--      回滚前必须导出 staff_authorization 全量版本并归档（授权历史属资质审计留痕），
--      生产执行须医务处/人事与 DBA 双签（不可逆脚本）。
--   3) 授权历史不得回灌（append-only 语义）：如需恢复，按原 version/审批人/时间重新授予，
--      并在业务说明中注明补录原因。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m4_staff;

COMMENT ON SCHEMA m4_staff IS
    'M4 人员档案与分级授权（staff_profile/staff_qualification/staff_authorization/staff_schedule/staff_training）；其他模块只能经 m4-api 访问';

-- -----------------------------------------------------------------------------
-- 1. staff_profile 人员档案
--    工号院内唯一（trim + 忽略大小写）且创建后不可改；userId 非空时一用户一档案。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_staff.staff_profile (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id           uuid,
    staff_no          varchar(32)  NOT NULL,
    name              varchar(64)  NOT NULL,
    department_id     uuid         NOT NULL,
    site_id           uuid,
    title             varchar(32)  NOT NULL,
    job_grade         varchar(32),
    employment_status varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    specialties       jsonb        NOT NULL DEFAULT '[]'::jsonb,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        uuid,
    version           integer      NOT NULL DEFAULT 1,
    CONSTRAINT pk_staff_profile PRIMARY KEY (id),
    CONSTRAINT ck_staff_profile_no CHECK (staff_no ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT ck_staff_profile_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_staff_profile_status CHECK (employment_status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_staff_profile_specialties CHECK (jsonb_typeof(specialties) = 'array'),
    CONSTRAINT ck_staff_profile_version CHECK (version >= 1)
);

-- 工号归一化唯一（服务层先查重返回 409，唯一索引是并发兜底）。
CREATE UNIQUE INDEX uk_staff_profile_no ON m4_staff.staff_profile (lower(btrim(staff_no)));
-- m0 用户与档案一一对应（userId 可空；空值不参与唯一）。
CREATE UNIQUE INDEX uk_staff_profile_user ON m4_staff.staff_profile (user_id)
    WHERE user_id IS NOT NULL;
CREATE INDEX idx_staff_profile_department ON m4_staff.staff_profile (department_id);
CREATE INDEX idx_staff_profile_status ON m4_staff.staff_profile (employment_status);

COMMENT ON TABLE m4_staff.staff_profile IS
    '人员档案（麻醉医师/护士）：工号唯一且不可改，userId 关联 m0 用户，在职状态 INACTIVE 后不得执业';
COMMENT ON COLUMN m4_staff.staff_profile.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m4_staff.staff_profile.user_id IS '关联 m0_platform.sys_user 标识（可空；非空时一人一档案）';
COMMENT ON COLUMN m4_staff.staff_profile.staff_no IS '院内工号（trim+大小写不敏感唯一，创建后不可改）';
COMMENT ON COLUMN m4_staff.staff_profile.name IS '姓名（仅本域业务展示；审计与日志不写姓名）';
COMMENT ON COLUMN m4_staff.staff_profile.department_id IS '所属科室组织标识（m0 逻辑外键，org_type=DEPT）';
COMMENT ON COLUMN m4_staff.staff_profile.site_id IS '院区标识（可空，m0 逻辑外键）';
COMMENT ON COLUMN m4_staff.staff_profile.title IS '职称（院内职称字典；取值【待确认】）';
COMMENT ON COLUMN m4_staff.staff_profile.job_grade IS '职级（可空；院内职级字典【待确认】）';
COMMENT ON COLUMN m4_staff.staff_profile.employment_status IS '在职状态：ACTIVE=在职，INACTIVE=离职/停用（不得执业，档案只读历史）';
COMMENT ON COLUMN m4_staff.staff_profile.specialties IS '专长标签（jsonb 字符串数组，仅作参考，不替代授权校验）';
COMMENT ON COLUMN m4_staff.staff_profile.version IS '乐观锁版本号（从 1 起；更新必须回传，过期 409）';

-- 档案守卫：工号/userId/创建留痕不可改、version 只增。
CREATE OR REPLACE FUNCTION m4_staff.fn_staff_profile_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.staff_no IS DISTINCT FROM OLD.staff_no
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_staff.staff_profile identity columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '工号/userId/创建留痕不可修改；离职请改 employment_status';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'm4_staff.staff_profile version must increase: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = '更新必须递增 version（乐观锁），不得静默覆盖';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_staff.fn_staff_profile_guard() IS
    '人员档案守卫：id/staff_no/user_id/created_* 不可变、version 只增（55000）';

CREATE TRIGGER trg_staff_profile_guard
    BEFORE UPDATE ON m4_staff.staff_profile
    FOR EACH ROW EXECUTE FUNCTION m4_staff.fn_staff_profile_guard();

-- -----------------------------------------------------------------------------
-- 2. staff_qualification 执业资质
--    同一人员同一证书号唯一；PUT 档案时提交列表为“最新快照”（替换式维护）。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_staff.staff_qualification (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    staff_id          uuid         NOT NULL,
    qualification_type varchar(32) NOT NULL,
    certificate_no    varchar(64)  NOT NULL,
    issuing_authority varchar(200) NOT NULL,
    valid_from        date         NOT NULL,
    valid_to          date,
    status            varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT pk_staff_qualification PRIMARY KEY (id),
    CONSTRAINT uk_staff_qualification UNIQUE (staff_id, certificate_no),
    CONSTRAINT ck_staff_qualification_type CHECK (qualification_type IN
        ('PRACTICE_LICENSE', 'SPECIALTY_CERT', 'NURSE_LICENSE', 'OTHER')),
    CONSTRAINT ck_staff_qualification_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_staff_qualification_valid CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_staff_qualification_no CHECK (btrim(certificate_no) <> ''),
    CONSTRAINT ck_staff_qualification_authority CHECK (btrim(issuing_authority) <> '')
);

CREATE INDEX idx_staff_qualification_staff ON m4_staff.staff_qualification (staff_id);
CREATE INDEX idx_staff_qualification_valid_to ON m4_staff.staff_qualification (valid_to)
    WHERE valid_to IS NOT NULL;

COMMENT ON TABLE m4_staff.staff_qualification IS
    '执业资质（执业资格/专业资质/护士执业证）：同一人员同一证书号唯一，提交列表为最新快照';
COMMENT ON COLUMN m4_staff.staff_qualification.staff_id IS '人员档案标识（逻辑外键 m4_staff.staff_profile）';
COMMENT ON COLUMN m4_staff.staff_qualification.qualification_type IS
    '资质类型：PRACTICE_LICENSE/SPECIALTY_CERT/NURSE_LICENSE/OTHER';
COMMENT ON COLUMN m4_staff.staff_qualification.certificate_no IS '证书编号（仅本域存储与展示，不写入审计/日志）';
COMMENT ON COLUMN m4_staff.staff_qualification.issuing_authority IS '签发机构';
COMMENT ON COLUMN m4_staff.staff_qualification.valid_from IS '有效期起（含）';
COMMENT ON COLUMN m4_staff.staff_qualification.valid_to IS '有效期止（含）；NULL 表示长期有效';
COMMENT ON COLUMN m4_staff.staff_qualification.status IS '状态：ACTIVE=有效，REVOKED=已撤销（吊销后不可用于执业判断）';

-- -----------------------------------------------------------------------------
-- 3. staff_authorization 分级授权（append-only 历史 + 当前视图）
--    每次 GRANT/REVOKE 写入新 version；旧版本保留，不允许 UPDATE/DELETE/TRUNCATE。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_staff.staff_authorization (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    staff_id      uuid         NOT NULL,
    version       integer      NOT NULL,
    level         varchar(8)   NOT NULL,
    surgery_scopes jsonb       NOT NULL,
    age_min       integer      NOT NULL,
    age_max       integer      NOT NULL,
    asa_max       varchar(8)   NOT NULL,
    valid_from    date         NOT NULL,
    valid_to      date         NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    approved_by   uuid         NOT NULL,
    approved_at   timestamptz  NOT NULL DEFAULT now(),
    change_reason varchar(200),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    uuid,
    CONSTRAINT pk_staff_authorization PRIMARY KEY (id),
    CONSTRAINT uk_staff_authorization_version UNIQUE (staff_id, version),
    CONSTRAINT ck_staff_authorization_level CHECK (level IN ('R1', 'R2', 'R3', 'R4', 'R5')),
    CONSTRAINT ck_staff_authorization_asa CHECK (asa_max IN
        ('ASA1', 'ASA2', 'ASA3', 'ASA4', 'ASA5')),
    CONSTRAINT ck_staff_authorization_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_staff_authorization_age CHECK (age_min >= 0 AND age_max >= age_min
        AND age_max <= 120),
    CONSTRAINT ck_staff_authorization_valid CHECK (valid_to >= valid_from),
    CONSTRAINT ck_staff_authorization_scopes CHECK (jsonb_typeof(surgery_scopes) = 'array'
        AND jsonb_array_length(surgery_scopes) >= 1),
    CONSTRAINT ck_staff_authorization_version_positive CHECK (version >= 1)
);

CREATE INDEX idx_staff_authorization_staff ON m4_staff.staff_authorization (staff_id, version DESC);

COMMENT ON TABLE m4_staff.staff_authorization IS
    '分级授权版本（append-only）：级别 R1–R5 + 术式范围/年龄段/ASA 上限/有效期/审批人；每次授予或撤销写入新版本';
COMMENT ON COLUMN m4_staff.staff_authorization.staff_id IS '人员档案标识（逻辑外键 m4_staff.staff_profile）';
COMMENT ON COLUMN m4_staff.staff_authorization.version IS '授权版本号（从 1 起，只增；撤销也写入新版本）';
COMMENT ON COLUMN m4_staff.staff_authorization.level IS '授权级别 R1–R5（级别含义与默认映射【待确认】）';
COMMENT ON COLUMN m4_staff.staff_authorization.surgery_scopes IS
    '术式范围（jsonb 字符串数组；"*" 表示全部术式/术式类别编码）';
COMMENT ON COLUMN m4_staff.staff_authorization.age_min IS '可实施年龄段下限（岁，含）';
COMMENT ON COLUMN m4_staff.staff_authorization.age_max IS '可实施年龄段上限（岁，含）';
COMMENT ON COLUMN m4_staff.staff_authorization.asa_max IS 'ASA 分级上限（含）：ASA1–ASA5';
COMMENT ON COLUMN m4_staff.staff_authorization.valid_from IS '授权生效日期（含）';
COMMENT ON COLUMN m4_staff.staff_authorization.valid_to IS '授权失效日期（含）';
COMMENT ON COLUMN m4_staff.staff_authorization.status IS
    '状态：ACTIVE=生效版本，REVOKED=已撤销版本（历史保留，不删除）';
COMMENT ON COLUMN m4_staff.staff_authorization.approved_by IS '审批人用户标识（授权属临床资质决策，须可追溯）';
COMMENT ON COLUMN m4_staff.staff_authorization.change_reason IS '变更/撤销原因（不含患者信息）';

-- 当前版本视图：每个人员取 version 最大的一行（撤销后当前版本为 REVOKED，history 保留全过程）。
CREATE OR REPLACE VIEW m4_staff.staff_authorization_current AS
SELECT DISTINCT ON (staff_id) *
  FROM m4_staff.staff_authorization
 ORDER BY staff_id, version DESC;

COMMENT ON VIEW m4_staff.staff_authorization_current IS
    '分级授权当前版本视图（每人员 version 最大的行；撤销后当前版本 status=REVOKED）';

-- append-only 守卫（行级 UPDATE/DELETE + 语句级 TRUNCATE 一律 55000）。
CREATE OR REPLACE FUNCTION m4_staff.fn_reject_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm4_staff append-only table rejects %', TG_OP
        USING ERRCODE = '55000',
              HINT = '分级授权历史只增不改：请写入新版本，不得修改或删除历史';
END;
$$;

CREATE OR REPLACE FUNCTION m4_staff.fn_reject_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'm4_staff append-only table rejects TRUNCATE'
        USING ERRCODE = '55000',
              HINT = '分级授权历史只增不改：TRUNCATE 被拒绝';
END;
$$;

COMMENT ON FUNCTION m4_staff.fn_reject_mutation() IS
    'append-only 行级守卫：UPDATE/DELETE 拒绝（55000）';
COMMENT ON FUNCTION m4_staff.fn_reject_truncate() IS
    'append-only 语句级守卫：TRUNCATE 拒绝（55000）';

CREATE TRIGGER trg_staff_authorization_no_update
    BEFORE UPDATE ON m4_staff.staff_authorization
    FOR EACH ROW EXECUTE FUNCTION m4_staff.fn_reject_mutation();
CREATE TRIGGER trg_staff_authorization_no_delete
    BEFORE DELETE ON m4_staff.staff_authorization
    FOR EACH ROW EXECUTE FUNCTION m4_staff.fn_reject_mutation();
CREATE TRIGGER trg_staff_authorization_no_truncate
    BEFORE TRUNCATE ON m4_staff.staff_authorization
    FOR EACH STATEMENT EXECUTE FUNCTION m4_staff.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 4. staff_schedule 排班
--    唯一冲突约束：同一人员同日期同班次、同一术间同日期同班次（仅 PLANNED 占用名额；
--    取消后释放）。服务层先查冲突返回 409 + details，唯一索引是并发兜底。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_staff.staff_schedule (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    staff_id      uuid         NOT NULL,
    schedule_date date         NOT NULL,
    shift         varchar(16)  NOT NULL,
    room_id       uuid,
    department_id uuid         NOT NULL,
    status        varchar(16)  NOT NULL DEFAULT 'PLANNED',
    note          varchar(200),
    cancelled_at  timestamptz,
    cancelled_by  uuid,
    cancel_reason varchar(200),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       integer      NOT NULL DEFAULT 1,
    CONSTRAINT pk_staff_schedule PRIMARY KEY (id),
    CONSTRAINT ck_staff_schedule_shift CHECK (shift IN ('DAY', 'NIGHT', 'ON_CALL')),
    CONSTRAINT ck_staff_schedule_status CHECK (status IN ('PLANNED', 'CANCELLED')),
    CONSTRAINT ck_staff_schedule_cancel CHECK (
        (status = 'PLANNED' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL)
    ),
    CONSTRAINT ck_staff_schedule_version CHECK (version >= 1),
    CONSTRAINT ck_staff_schedule_note CHECK (note IS NULL OR btrim(note) <> '')
);

-- 同一人员同一日期同一班次唯一（仅有效排班占用）。
CREATE UNIQUE INDEX uk_staff_schedule_staff_slot
    ON m4_staff.staff_schedule (staff_id, schedule_date, shift)
    WHERE status = 'PLANNED';
-- 同一术间同一日期同一班次唯一（room_id 为空不参与）。
CREATE UNIQUE INDEX uk_staff_schedule_room_slot
    ON m4_staff.staff_schedule (room_id, schedule_date, shift)
    WHERE status = 'PLANNED' AND room_id IS NOT NULL;
CREATE INDEX idx_staff_schedule_date ON m4_staff.staff_schedule (schedule_date);
CREATE INDEX idx_staff_schedule_department ON m4_staff.staff_schedule (department_id);
CREATE INDEX idx_staff_schedule_staff ON m4_staff.staff_schedule (staff_id, schedule_date);

COMMENT ON TABLE m4_staff.staff_schedule IS
    '排班：人员 + 日期 + 班次 + 术间/科室；同人员同日同班次、同术间同日同班次唯一（仅 PLANNED 占用，取消释放）';
COMMENT ON COLUMN m4_staff.staff_schedule.staff_id IS '人员档案标识（逻辑外键 m4_staff.staff_profile）';
COMMENT ON COLUMN m4_staff.staff_schedule.schedule_date IS '排班日期（Asia/Shanghai 自然日）';
COMMENT ON COLUMN m4_staff.staff_schedule.shift IS '班次：DAY=白班，NIGHT=夜班，ON_CALL=备班（班次口径【待确认】）';
COMMENT ON COLUMN m4_staff.staff_schedule.room_id IS '术间标识（可空；术间主数据归属 M1，本域只做冲突检测）';
COMMENT ON COLUMN m4_staff.staff_schedule.department_id IS '科室组织标识（m0 逻辑外键，org_type=DEPT）';
COMMENT ON COLUMN m4_staff.staff_schedule.status IS '状态：PLANNED=已排班，CANCELLED=已取消（保留记录，释放名额）';
COMMENT ON COLUMN m4_staff.staff_schedule.note IS '备注（不含患者信息）';
COMMENT ON COLUMN m4_staff.staff_schedule.cancel_reason IS '取消原因（不含患者信息）';
COMMENT ON COLUMN m4_staff.staff_schedule.version IS '乐观锁版本号（从 1 起；更新/取消必须回传，过期 409）';

-- 排班守卫：创建留痕不可改、version 只增、已取消不得复活。
CREATE OR REPLACE FUNCTION m4_staff.fn_staff_schedule_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_staff.staff_schedule identity columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '创建留痕不可修改';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'm4_staff.staff_schedule version must increase: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = '更新/取消必须递增 version（乐观锁），不得静默覆盖';
    END IF;
    IF OLD.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'm4_staff.staff_schedule is cancelled and immutable'
            USING ERRCODE = '55000',
                  HINT = '已取消的排班不可复活或修改；请新建排班';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_staff.fn_staff_schedule_guard() IS
    '排班守卫：id/created_* 不可变、version 只增、已取消记录不可修改（55000）';

CREATE TRIGGER trg_staff_schedule_guard
    BEFORE UPDATE ON m4_staff.staff_schedule
    FOR EACH ROW EXECUTE FUNCTION m4_staff.fn_staff_schedule_guard();

-- -----------------------------------------------------------------------------
-- 5. staff_training 培训与继续教育
-- -----------------------------------------------------------------------------
CREATE TABLE m4_staff.staff_training (
    id             uuid         NOT NULL DEFAULT gen_random_uuid(),
    staff_id       uuid         NOT NULL,
    course_code    varchar(64)  NOT NULL,
    course_name    varchar(200) NOT NULL,
    completed_on   date         NOT NULL,
    expires_on     date,
    certificate_no varchar(64),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     uuid,
    CONSTRAINT pk_staff_training PRIMARY KEY (id),
    CONSTRAINT uk_staff_training_course UNIQUE (staff_id, course_code),
    CONSTRAINT ck_staff_training_code CHECK (course_code ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_staff_training_name CHECK (btrim(course_name) <> ''),
    CONSTRAINT ck_staff_training_valid CHECK (expires_on IS NULL OR expires_on >= completed_on)
);

CREATE INDEX idx_staff_training_staff ON m4_staff.staff_training (staff_id, completed_on DESC);
CREATE INDEX idx_staff_training_expires ON m4_staff.staff_training (expires_on)
    WHERE expires_on IS NOT NULL;

COMMENT ON TABLE m4_staff.staff_training IS
    '培训与继续教育记录：课程编码/名称、完成/到期日期、证书号；同一人员同一课程编码唯一';
COMMENT ON COLUMN m4_staff.staff_training.staff_id IS '人员档案标识（逻辑外键 m4_staff.staff_profile）';
COMMENT ON COLUMN m4_staff.staff_training.course_code IS '课程编码（院内唯一；同一人员同一课程编码只能登记一次）';
COMMENT ON COLUMN m4_staff.staff_training.course_name IS '课程名称';
COMMENT ON COLUMN m4_staff.staff_training.completed_on IS '完成日期（不得晚于今天，服务层校验）';
COMMENT ON COLUMN m4_staff.staff_training.expires_on IS '到期日期（NULL 表示长期有效；不得早于完成日期）';
COMMENT ON COLUMN m4_staff.staff_training.certificate_no IS '证书编号（不写入审计与日志）';

-- -----------------------------------------------------------------------------
-- 6. 权限点与最小授权（M4-M13-01）
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000081', 'm4:staff:read', 'M4 人员档案只读', 'API',
     '/api/v1/staff', 'GET', NULL, 150),
    ('b0000000-0000-4000-8000-000000000082', 'm4:staff:write', 'M4 人员档案与分级授权维护', 'API',
     '/api/v1/staff', 'POST', NULL, 151),
    ('b0000000-0000-4000-8000-000000000083', 'm4:schedule:manage', 'M4 人员排班管理', 'API',
     '/api/v1/staff/schedules', 'POST', NULL, 152),
    ('b0000000-0000-4000-8000-000000000084', 'm4:training:manage', 'M4 培训记录管理', 'API',
     '/api/v1/staff/trainings', 'POST', NULL, 153);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN（信息科运维）：人员域四项全授（排障/初始化，最小集内）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000081'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000082'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000083'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000084'),
    -- SYS_OR_MANAGER（手术室管理员）：档案只读 + 排班管理（排班职责，不授授权维护）
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000081'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000083'),
    -- SYS_ANESTHESIOLOGIST（麻醉医师）：档案只读 + 资质/授权复核 + 培训登记（不授排班）
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000081'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000082'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000084');

-- -----------------------------------------------------------------------------
-- 7. 迁移自检：表/视图/函数/权限与最小授权必须齐全（fail-fast，避免半套 schema 上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_tables integer;
    missing_views integer;
    missing_permissions integer;
    missing_grants integer;
BEGIN
    SELECT count(*) INTO missing_tables
      FROM (VALUES
            ('m4_staff.staff_profile'), ('m4_staff.staff_qualification'),
            ('m4_staff.staff_authorization'), ('m4_staff.staff_schedule'),
            ('m4_staff.staff_training')) AS t(name)
     WHERE to_regclass(t.name) IS NULL;
    IF missing_tables > 0 THEN
        RAISE EXCEPTION 'V21 自检失败：缺少 % 张表', missing_tables;
    END IF;

    SELECT count(*) INTO missing_views
      FROM (VALUES ('m4_staff.staff_authorization_current')) AS v(name)
     WHERE to_regclass(v.name) IS NULL;
    IF missing_views > 0 THEN
        RAISE EXCEPTION 'V21 自检失败：缺少 % 个视图', missing_views;
    END IF;

    SELECT count(*) INTO missing_permissions
      FROM (VALUES ('m4:staff:read'), ('m4:staff:write'), ('m4:schedule:manage'),
                   ('m4:training:manage')) AS p(code)
     WHERE NOT EXISTS (
         SELECT 1 FROM m0_platform.sys_permission sp WHERE sp.code = p.code
     );
    IF missing_permissions > 0 THEN
        RAISE EXCEPTION 'V21 自检失败：缺少 % 个权限点', missing_permissions;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm4:staff:read'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:staff:write'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:schedule:manage'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:training:manage'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:staff:read'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:schedule:manage'),
            ('a0000000-0000-4000-8000-000000000002', 'm4:staff:read'),
            ('a0000000-0000-4000-8000-000000000002', 'm4:staff:write'),
            ('a0000000-0000-4000-8000-000000000002', 'm4:training:manage')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V21 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;
END;
$$;
