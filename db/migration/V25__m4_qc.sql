-- =============================================================================
-- ANESOP 数据库迁移 V25：M4 质控指标、不良事件 RCA、病历质量抽查与上报导出（M4-M17-01 / E-M17）
-- 目标库：PostgreSQL 16；schema：m4_qc
--
-- 设计依据：
--   方案书 §2.9（M7 质量控制：指标体系、自动计算与钻取、不良事件与 RCA/PDCA、质控看板与
--   报告、病历质量抽查、规则版本管理）、§2.9.2（指标分子分母可追溯到原始记录条目；
--   不良事件上报后 48h 内完成分级初评；规则变更留版本，跨期指标按当期版本计算）、
--   §2.5.3 表 2-6 I-10（国家/省级麻醉质控平台上报）、§3.4.3（时间与时区）
--   契约：packages/contracts/openapi/qc.yaml 0.1.0
--   任务卡：docs/ai/tasks/M4-backlog.md M4-M17-01
--   ADR：docs/adr/0022-质控与不良事件口径.md
--
-- 内容（expand 段：只新增 schema/表/索引/约束/触发器/权限点/参考指标，不改写 V1–V24）：
--   1) m4_qc.qc_indicator         指标定义（按 code + version 版本化，口径/阈值/数据来源）。
--   2) m4_qc.qc_indicator_result  指标结果快照（周期 + 科室 + 版本唯一，分子/分母/值/达标）。
--   3) m4_qc.adverse_event        不良事件主体（append-only 业务内容 + 状态机
--                                 REPORTED→RCA→RECTIFYING→CLOSED）。
--   4) m4_qc.adverse_rca          RCA 记录（append-only；时间线/根因/整改/责任人/期限，
--                                 复核与闭环字段一次性写入，一条事件一条 RCA）。
--   5) m4_qc.chart_review         病历质量抽查（评分项 jsonb、总分/满分/等级/缺陷计数）。
--   6) m4_qc.rectification        整改记录（append-only 内容；复核与闭环一次性写入，
--                                 一条抽查一条整改）。
--   7) 权限点 m4:qc:read|write|review|export（三段式编码），最小授予
--      SYS_ADMIN（四项）、SYS_QC_MANAGER（四项）、SYS_OR_MANAGER（read+write）。
--   8) 7 条**合成参考指标**（QC-SYN-*，provisional=true）作为指标目录骨架。
--
-- 临床/管理口径约定与【待确认】（详见 ADR-0022 与 docs/modules/m4/README.md）：
--   - **指标清单/阈值【待确认】**：本期落地 7 条合成参考指标（结构/过程/结果各覆盖），
--     计算来源在 data_source 中显式声明（M3 记录单/时间轴、M4 药品/耗材、M2 排程台次、
--     本域不良事件与病历抽查）。国家/省级质控平台正式指标目录、阈值与单位确认后，
--     以**新版本行**替换（code 不变、version 递增），不原地修改历史口径。
--   - **上报格式【待确认】**：I-10 平台的文件/接口格式、字段映射、上报周期未确认；
--     本期只提供 CSV **占位模板**（导出不含描述/根因/整改等自由文本），不直连外部系统（EXT）。
--   - **匿名化【待确认】**：anonymous=true 时对外只显示“匿名上报”，内部仍保存上报人标识
--     供追溯与独立复核；是否允许对质控专员也匿名待院方确认。
--   - **闭环时限【待确认】**：方案书要求上报后 48h 内完成分级初评；本期只记录状态与时间，
--     不内置超时拒绝（避免把未确认的时限写成硬规则），由看板/报表提示超期。
--   - **独立复核**：RCA/整改闭环复核人不得为上报人/责任人（服务层 422）；是否要求双人
--     复核、复核人资质（质控专员/科主任）待院方确认。
--   - **病历抽查合格线【待确认】**：本期以 total_score / max_score ≥ 90% 为合格，
--     等级 A≥95%、B≥90%、C≥80%、D<80%；评分项白名单固定四项
--     （完整性/时效性/签名/逻辑一致性），权重由满分 maxScore 体现。
--   - **脱敏**：本域不存患者标识；adverse_event.description 为已脱敏描述（服务端拒绝
--     疑似患者标识入参）；chart_review.items 的 defect_note 为脱敏缺陷说明，不含患者信息；
--     导出不输出任何自由文本原文。
--
-- 运行时权限要求：append-only 与状态机守卫依赖触发器/函数，应用运行账号不得拥有 DDL 权限
-- （CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写留痕；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.20）：
--   1) 应用回滚：下线 /v1/qc/* 端点与前端“质控指标/不良事件/病历抽查”菜单；质控数据保留
--      （不参与临床记录与排程；指标结果可由源数据重算）。
--   2) 库层前向修复（需 DBA 审批 + 备份；不良事件与 RCA 属质量追溯留痕，回滚前必须导出归档）：
--      DROP TRIGGER IF EXISTS trg_adverse_event_no_delete ON m4_qc.adverse_event;
--      DROP TRIGGER IF EXISTS trg_adverse_event_no_truncate ON m4_qc.adverse_event;
--      DROP TRIGGER IF EXISTS trg_adverse_event_guard ON m4_qc.adverse_event;
--      DROP TRIGGER IF EXISTS trg_adverse_rca_guard ON m4_qc.adverse_rca;
--      DROP TRIGGER IF EXISTS trg_adverse_rca_no_delete ON m4_qc.adverse_rca;
--      DROP TRIGGER IF EXISTS trg_chart_review_guard ON m4_qc.chart_review;
--      DROP TRIGGER IF EXISTS trg_rectification_guard ON m4_qc.rectification;
--      DROP TRIGGER IF EXISTS trg_qc_indicator_guard ON m4_qc.qc_indicator;
--      DROP TRIGGER IF EXISTS trg_qc_indicator_no_delete ON m4_qc.qc_indicator;
--      DROP TRIGGER IF EXISTS trg_qc_indicator_no_truncate ON m4_qc.qc_indicator;
--      DROP FUNCTION IF EXISTS m4_qc.fn_adverse_event_guard();
--      DROP FUNCTION IF EXISTS m4_qc.fn_adverse_rca_guard();
--      DROP FUNCTION IF EXISTS m4_qc.fn_chart_review_guard();
--      DROP FUNCTION IF EXISTS m4_qc.fn_rectification_guard();
--      DROP FUNCTION IF EXISTS m4_qc.fn_indicator_guard();
--      DROP FUNCTION IF EXISTS m4_qc.fn_reject_mutation();
--      DROP FUNCTION IF EXISTS m4_qc.fn_reject_truncate();
--      DROP TABLE m4_qc.rectification;
--      DROP TABLE m4_qc.chart_review;
--      DROP TABLE m4_qc.adverse_rca;
--      DROP TABLE m4_qc.adverse_event;
--      DROP TABLE m4_qc.qc_indicator_result;
--      DROP TABLE m4_qc.qc_indicator;
--      DROP SCHEMA IF EXISTS m4_qc;
--      DELETE FROM m0_platform.sys_role_permission
--       WHERE permission_id IN ('b0000000-0000-4000-8000-000000000079',
--                               'b0000000-0000-4000-8000-00000000007a',
--                               'b0000000-0000-4000-8000-00000000007b',
--                               'b0000000-0000-4000-8000-00000000007c');
--      DELETE FROM m0_platform.sys_permission
--       WHERE code IN ('m4:qc:read', 'm4:qc:write', 'm4:qc:review', 'm4:qc:export');
--   3) 回滚顺序：先删授权再删权限点（外键 sys_role_permission.permission_id），
--      先删子表再删主表；回滚后重新登录刷新 JWT 权限 claim。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m4_qc;

COMMENT ON SCHEMA m4_qc IS
    'M4 质控指标定义与结果、不良事件与 RCA、病历质量抽查与整改（qc_indicator/qc_indicator_result/adverse_event/adverse_rca/chart_review/rectification）；其他模块只能经 m4-api 访问';

-- -----------------------------------------------------------------------------
-- 0. append-only 通用守卫函数（指标定义与事件/抽查/整改共用）
--    先于各表触发器定义，避免依赖脚本内对象顺序。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m4_qc.fn_reject_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000',
              HINT = '质控留痕不得修改或删除；更正请走数据修复流程并留档';
END;
$$;

CREATE OR REPLACE FUNCTION m4_qc.fn_reject_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: TRUNCATE is not permitted', TG_TABLE_NAME
        USING ERRCODE = '55000',
              HINT = '质控留痕不得清空；归档请走离线追溯库';
END;
$$;

COMMENT ON FUNCTION m4_qc.fn_reject_mutation() IS 'append-only 行级守卫：UPDATE/DELETE 拒绝（55000）';
COMMENT ON FUNCTION m4_qc.fn_reject_truncate() IS 'append-only 语句级守卫：TRUNCATE 拒绝（55000）';

-- -----------------------------------------------------------------------------
-- 1. qc_indicator 指标定义（版本化）
--    一条记录 = 一个口径版本；语义变更必须新增版本行，历史结果保留旧版本引用。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_qc.qc_indicator (
    id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
    code               varchar(64)  NOT NULL,
    name               varchar(200) NOT NULL,
    category           varchar(16)  NOT NULL,
    definition         varchar(500) NOT NULL,
    data_source        varchar(300) NOT NULL,
    numerator_label    varchar(120) NOT NULL,
    denominator_label  varchar(120) NOT NULL,
    unit               varchar(24)  NOT NULL,
    threshold_operator varchar(8)   NOT NULL,
    threshold_value    numeric(9, 2) NOT NULL,
    version            varchar(32)  NOT NULL,
    effective_from     date         NOT NULL,
    effective_to       date,
    status             varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    provisional        boolean      NOT NULL DEFAULT true,
    in_target_statistics boolean    NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid,
    CONSTRAINT pk_qc_indicator PRIMARY KEY (id),
    CONSTRAINT uk_qc_indicator_code_version UNIQUE (code, version),
    CONSTRAINT ck_qc_indicator_category CHECK (category IN ('STRUCTURE', 'PROCESS', 'OUTCOME')),
    CONSTRAINT ck_qc_indicator_unit CHECK (unit IN ('PERCENT', 'PER_1000_CASES')),
    CONSTRAINT ck_qc_indicator_threshold_operator CHECK (threshold_operator IN ('GTE', 'LTE')),
    CONSTRAINT ck_qc_indicator_threshold_value CHECK (threshold_value >= 0),
    CONSTRAINT ck_qc_indicator_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_qc_indicator_period CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    ),
    CONSTRAINT ck_qc_indicator_code CHECK (code ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_qc_indicator_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_qc_indicator_definition_not_blank CHECK (btrim(definition) <> '')
);

CREATE INDEX idx_qc_indicator_category ON m4_qc.qc_indicator (category);
CREATE INDEX idx_qc_indicator_status ON m4_qc.qc_indicator (status);

COMMENT ON TABLE m4_qc.qc_indicator IS
    '质控指标定义（按 code + version 版本化）：口径/数据来源/分子分母含义/阈值；语义变更只新增版本行';
COMMENT ON COLUMN m4_qc.qc_indicator.id IS '主键 UUID';
COMMENT ON COLUMN m4_qc.qc_indicator.code IS '指标编码（院内唯一标识指标；版本化时不变）';
COMMENT ON COLUMN m4_qc.qc_indicator.name IS '指标名称（中文）';
COMMENT ON COLUMN m4_qc.qc_indicator.category IS '类别：STRUCTURE=结构、PROCESS=过程、OUTCOME=结果';
COMMENT ON COLUMN m4_qc.qc_indicator.definition IS '口径说明（分子/分母定义；可追溯到原始记录条目）';
COMMENT ON COLUMN m4_qc.qc_indicator.data_source IS '数据来源声明（M3 记录单/时间轴、M4 药品/耗材、M2 排程、本域数据）';
COMMENT ON COLUMN m4_qc.qc_indicator.numerator_label IS '分子含义（展示与钻取说明）';
COMMENT ON COLUMN m4_qc.qc_indicator.denominator_label IS '分母含义（展示与钻取说明）';
COMMENT ON COLUMN m4_qc.qc_indicator.unit IS '单位：PERCENT=百分比、PER_1000_CASES=每千台次';
COMMENT ON COLUMN m4_qc.qc_indicator.threshold_operator IS '达标判定方向：GTE=值≥阈值、LTE=值≤阈值';
COMMENT ON COLUMN m4_qc.qc_indicator.threshold_value IS '阈值（与 unit 同单位；【待确认】由院方确认）';
COMMENT ON COLUMN m4_qc.qc_indicator.version IS '口径版本（语义变更必须递增，不得原地改口径）';
COMMENT ON COLUMN m4_qc.qc_indicator.effective_from IS '生效起始本地日期（含）';
COMMENT ON COLUMN m4_qc.qc_indicator.effective_to IS '生效结束本地日期（含）；NULL 表示长期有效';
COMMENT ON COLUMN m4_qc.qc_indicator.status IS '状态：ACTIVE=生效、RETIRED=停用（停用后不参与计算，历史结果保留）';
COMMENT ON COLUMN m4_qc.qc_indicator.provisional IS
    '是否合成参考指标：true 表示口径/阈值【待确认】（ADR-0022），正式目录确认后升版本替换';
COMMENT ON COLUMN m4_qc.qc_indicator.in_target_statistics IS
    '是否计入达标统计：false 表示仅作结构/展示合规口径，targetMet 固定 false（QC-SYN-PROC-004，Q-1 确认前）';

-- 指标定义守卫：身份/口径/阈值不可改；只允许 ACTIVE→RETIRED 与补录 effective_to。
CREATE OR REPLACE FUNCTION m4_qc.fn_indicator_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.code IS DISTINCT FROM OLD.code
       OR NEW.version IS DISTINCT FROM OLD.version
       OR NEW.name IS DISTINCT FROM OLD.name
       OR NEW.category IS DISTINCT FROM OLD.category
       OR NEW.definition IS DISTINCT FROM OLD.definition
       OR NEW.data_source IS DISTINCT FROM OLD.data_source
       OR NEW.numerator_label IS DISTINCT FROM OLD.numerator_label
       OR NEW.denominator_label IS DISTINCT FROM OLD.denominator_label
       OR NEW.unit IS DISTINCT FROM OLD.unit
       OR NEW.threshold_operator IS DISTINCT FROM OLD.threshold_operator
       OR NEW.threshold_value IS DISTINCT FROM OLD.threshold_value
       OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
       OR NEW.provisional IS DISTINCT FROM OLD.provisional
       OR NEW.in_target_statistics IS DISTINCT FROM OLD.in_target_statistics
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_qc.qc_indicator definition columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '口径/阈值变更必须新增版本行（code 不变、version 递增），不得原地修改';
    END IF;
    IF OLD.status = 'RETIRED' AND NEW.status = 'ACTIVE' THEN
        RAISE EXCEPTION 'm4_qc.qc_indicator cannot be re-activated'
            USING ERRCODE = '55000',
                  HINT = '停用后需启用请新增版本行';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_qc.fn_indicator_guard() IS
    '指标定义守卫：身份/口径/阈值不可变（55000），只允许 ACTIVE→RETIRED';

CREATE TRIGGER trg_qc_indicator_guard
    BEFORE UPDATE ON m4_qc.qc_indicator
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_indicator_guard();

-- 指标定义是结果快照的口径依据：DELETE/TRUNCATE 一律拒绝（55000），避免删定义抹掉追溯链；
-- 正式目录替换只新增版本行，历史行保持 ACTIVE/RETIRED 状态可查。
CREATE TRIGGER trg_qc_indicator_no_delete
    BEFORE DELETE ON m4_qc.qc_indicator
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_reject_mutation();
CREATE TRIGGER trg_qc_indicator_no_truncate
    BEFORE TRUNCATE ON m4_qc.qc_indicator
    FOR EACH STATEMENT EXECUTE FUNCTION m4_qc.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 2. qc_indicator_result 指标结果快照
--    唯一键 = 指标 + 周期 + 科室（dept_id 用哨兵全 0 表示全院）：重算覆盖同一快照，
--    不产生多行；跨版本的历史结果因 indicator_version 不同而独立保留。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_qc.qc_indicator_result (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    indicator_id      uuid          NOT NULL,
    indicator_code    varchar(64)   NOT NULL,
    indicator_version varchar(32)   NOT NULL,
    period_from       date          NOT NULL,
    period_to         date          NOT NULL,
    dept_id           uuid          NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    numerator         integer       NOT NULL,
    denominator       integer       NOT NULL,
    value             numeric(12, 2) NOT NULL,
    target_met        boolean       NOT NULL,
    computed_at       timestamptz   NOT NULL DEFAULT now(),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_qc_indicator_result PRIMARY KEY (id),
    CONSTRAINT fk_qc_indicator_result_indicator FOREIGN KEY (indicator_id)
        REFERENCES m4_qc.qc_indicator (id) ON DELETE RESTRICT,
    CONSTRAINT uk_qc_indicator_result UNIQUE (indicator_id, period_from, period_to, dept_id),
    CONSTRAINT ck_qc_indicator_result_period CHECK (period_to >= period_from),
    CONSTRAINT ck_qc_indicator_result_numerator CHECK (numerator >= 0),
    CONSTRAINT ck_qc_indicator_result_denominator CHECK (denominator >= 0),
    CONSTRAINT ck_qc_indicator_result_value CHECK (value >= 0)
);

CREATE INDEX idx_qc_indicator_result_period ON m4_qc.qc_indicator_result (period_from, period_to);
CREATE INDEX idx_qc_indicator_result_dept ON m4_qc.qc_indicator_result (dept_id);
CREATE INDEX idx_qc_indicator_result_code ON m4_qc.qc_indicator_result (indicator_code);

COMMENT ON TABLE m4_qc.qc_indicator_result IS
    '指标结果快照：指标+周期+科室唯一（dept_id 全 0 哨兵=全院）；重算覆盖同一快照，跨版本历史结果独立保留';
COMMENT ON COLUMN m4_qc.qc_indicator_result.indicator_id IS '指标定义标识（FK → qc_indicator）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.indicator_code IS '指标编码快照';
COMMENT ON COLUMN m4_qc.qc_indicator_result.indicator_version IS '计算所用口径版本（跨期按当期版本）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.period_from IS '周期起始本地日期（含）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.period_to IS '周期结束本地日期（含）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.dept_id IS
    '科室标识；全 0 UUID 表示全院口径（避免 NULL 在唯一键中重复）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.numerator IS '分子（整数计数，由服务端从 data_source 计算）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.denominator IS '分母（整数计数；0 表示无基数，不判达标）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.value IS '指标值（PERCENT 或 PER_1000_CASES 换算，2 位小数）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.target_met IS '是否达标（分母为 0 时固定 false）';
COMMENT ON COLUMN m4_qc.qc_indicator_result.computed_at IS '计算时间（重算时更新）';

-- -----------------------------------------------------------------------------
-- 3. adverse_event 不良事件主体（append-only 业务内容 + 状态机）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_qc.adverse_event (
    id               uuid         NOT NULL DEFAULT gen_random_uuid(),
    event_no         varchar(32)  NOT NULL,
    event_type       varchar(24)  NOT NULL,
    severity         varchar(16)  NOT NULL,
    occurred_at      timestamptz  NOT NULL,
    dept_id          uuid         NOT NULL,
    dept_name        varchar(120),
    schedule_id      uuid,
    description      varchar(500) NOT NULL,
    reporter_id      uuid,
    reporter_name    varchar(64)  NOT NULL,
    anonymous        boolean      NOT NULL DEFAULT false,
    status           varchar(16)  NOT NULL DEFAULT 'REPORTED',
    status_changed_at timestamptz NOT NULL DEFAULT now(),
    version          integer      NOT NULL DEFAULT 1,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid,
    CONSTRAINT pk_adverse_event PRIMARY KEY (id),
    CONSTRAINT uk_adverse_event_no UNIQUE (event_no),
    CONSTRAINT ck_adverse_event_type CHECK (event_type IN (
        'RESPIRATORY', 'CIRCULATORY', 'MEDICATION', 'TRANSFUSION', 'EQUIPMENT',
        'NERVE_INJURY', 'AWARENESS', 'FALL', 'OTHER')),
    CONSTRAINT ck_adverse_event_severity CHECK (severity IN (
        'LEVEL_I', 'LEVEL_II', 'LEVEL_III', 'LEVEL_IV')),
    CONSTRAINT ck_adverse_event_status CHECK (status IN (
        'REPORTED', 'RCA', 'RECTIFYING', 'CLOSED')),
    CONSTRAINT ck_adverse_event_version CHECK (version >= 1),
    CONSTRAINT ck_adverse_event_description_not_blank CHECK (btrim(description) <> ''),
    CONSTRAINT ck_adverse_event_reporter_not_blank CHECK (btrim(reporter_name) <> '')
);

CREATE INDEX idx_adverse_event_occurred_at ON m4_qc.adverse_event (occurred_at);
CREATE INDEX idx_adverse_event_status ON m4_qc.adverse_event (status);
CREATE INDEX idx_adverse_event_dept ON m4_qc.adverse_event (dept_id);
CREATE INDEX idx_adverse_event_schedule ON m4_qc.adverse_event (schedule_id);

COMMENT ON TABLE m4_qc.adverse_event IS
    '不良事件主体（append-only 业务内容 + 状态机 REPORTED→RCA→RECTIFYING→CLOSED，越级被触发器拒绝）';
COMMENT ON COLUMN m4_qc.adverse_event.event_no IS '事件号（AE-<yyyyMMdd>-<8 位十六进制>，服务端生成、唯一）';
COMMENT ON COLUMN m4_qc.adverse_event.event_type IS
    '事件类型：RESPIRATORY/CIRCULATORY/MEDICATION/TRANSFUSION/EQUIPMENT/NERVE_INJURY/AWARENESS/FALL/OTHER（院内字典【待确认】）';
COMMENT ON COLUMN m4_qc.adverse_event.severity IS
    '事件分级：LEVEL_I–LEVEL_IV（I 级最重；分级定义与映射【待确认】）';
COMMENT ON COLUMN m4_qc.adverse_event.occurred_at IS '发生时间（RFC 3339 落库为 timestamptz）';
COMMENT ON COLUMN m4_qc.adverse_event.dept_id IS '科室标识（数据范围过滤依据）';
COMMENT ON COLUMN m4_qc.adverse_event.dept_name IS '科室名称快照（组织主数据；不含患者信息）';
COMMENT ON COLUMN m4_qc.adverse_event.schedule_id IS
    '关联台次标识（逻辑关联 m1_sched.schedule，跨模块不建外键；可空）';
COMMENT ON COLUMN m4_qc.adverse_event.description IS
    '已脱敏事件描述（≤500 字；服务端拒绝疑似患者标识；导出不输出原文）';
COMMENT ON COLUMN m4_qc.adverse_event.reporter_id IS '上报人用户标识（匿名上报也保留，供追溯/独立复核）';
COMMENT ON COLUMN m4_qc.adverse_event.reporter_name IS '上报人姓名摘要（≤64）；匿名时对外返回“匿名上报”';
COMMENT ON COLUMN m4_qc.adverse_event.anonymous IS '是否匿名上报（匿名口径【待确认】，见 ADR-0022 Q-3）';
COMMENT ON COLUMN m4_qc.adverse_event.status IS '状态：REPORTED/RCA/RECTIFYING/CLOSED（只允许逐级前进）';
COMMENT ON COLUMN m4_qc.adverse_event.status_changed_at IS '最近一次状态变更时间';
COMMENT ON COLUMN m4_qc.adverse_event.version IS '状态机版本号（每次状态前移 +1，乐观并发）';

-- 不良事件守卫：业务内容/身份不可变；状态只允许逐级前进；version 必须 +1。
CREATE OR REPLACE FUNCTION m4_qc.fn_adverse_event_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.event_no IS DISTINCT FROM OLD.event_no
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.severity IS DISTINCT FROM OLD.severity
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
       OR NEW.dept_id IS DISTINCT FROM OLD.dept_id
       OR NEW.dept_name IS DISTINCT FROM OLD.dept_name
       OR NEW.schedule_id IS DISTINCT FROM OLD.schedule_id
       OR NEW.description IS DISTINCT FROM OLD.description
       OR NEW.reporter_id IS DISTINCT FROM OLD.reporter_id
       OR NEW.reporter_name IS DISTINCT FROM OLD.reporter_name
       OR NEW.anonymous IS DISTINCT FROM OLD.anonymous
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_qc.adverse_event business columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '不良事件业务内容 append-only；更正请新建事件并说明关联事件号';
    END IF;

    IF NEW.status = OLD.status THEN
        RAISE EXCEPTION 'm4_qc.adverse_event status must change on update: %', OLD.status
            USING ERRCODE = '55000',
                  HINT = '状态更新必须前移一级（REPORTED→RCA→RECTIFYING→CLOSED）';
    END IF;

    IF NOT (
        (OLD.status = 'REPORTED' AND NEW.status = 'RCA')
        OR (OLD.status = 'RCA' AND NEW.status = 'RECTIFYING')
        OR (OLD.status = 'RECTIFYING' AND NEW.status = 'CLOSED')
    ) THEN
        RAISE EXCEPTION 'm4_qc.adverse_event illegal status transition: % -> %',
            OLD.status, NEW.status
            USING ERRCODE = '55000',
                  HINT = '不允许越级/回退；必须逐级推进状态机';
    END IF;

    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'm4_qc.adverse_event version must increase by 1: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = '状态变更必须递增 version（乐观并发）';
    END IF;

    NEW.status_changed_at = now();
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_qc.fn_adverse_event_guard() IS
    '不良事件守卫：业务内容不可变、状态逐级前进、version +1（55000）';

CREATE TRIGGER trg_adverse_event_guard
    BEFORE UPDATE ON m4_qc.adverse_event
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_adverse_event_guard();
CREATE TRIGGER trg_adverse_event_no_delete
    BEFORE DELETE ON m4_qc.adverse_event
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_reject_mutation();
CREATE TRIGGER trg_adverse_event_no_truncate
    BEFORE TRUNCATE ON m4_qc.adverse_event
    FOR EACH STATEMENT EXECUTE FUNCTION m4_qc.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 4. adverse_rca RCA 记录（append-only 内容；复核/闭环字段一次性写入）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_qc.adverse_rca (
    id                  uuid         NOT NULL DEFAULT gen_random_uuid(),
    event_id            uuid         NOT NULL,
    event_no            varchar(32)  NOT NULL,
    timeline            jsonb        NOT NULL,
    root_cause          varchar(500) NOT NULL,
    corrective_action   varchar(500) NOT NULL,
    responsible_name    varchar(64)  NOT NULL,
    due_date            date         NOT NULL,
    status              varchar(16)  NOT NULL DEFAULT 'OPEN',
    reviewer_id         uuid,
    reviewer_name       varchar(64),
    reviewed_at         timestamptz,
    review_conclusion   varchar(300),
    closed_at           timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    created_by          uuid,
    CONSTRAINT pk_adverse_rca PRIMARY KEY (id),
    CONSTRAINT fk_adverse_rca_event FOREIGN KEY (event_id)
        REFERENCES m4_qc.adverse_event (id) ON DELETE RESTRICT,
    CONSTRAINT uk_adverse_rca_event UNIQUE (event_id),
    CONSTRAINT ck_adverse_rca_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_adverse_rca_timeline CHECK (
        jsonb_typeof(timeline) = 'array'
        AND jsonb_array_length(timeline) BETWEEN 1 AND 20
    ),
    CONSTRAINT ck_adverse_rca_root_cause_not_blank CHECK (btrim(root_cause) <> ''),
    CONSTRAINT ck_adverse_rca_action_not_blank CHECK (btrim(corrective_action) <> ''),
    CONSTRAINT ck_adverse_rca_responsible_not_blank CHECK (btrim(responsible_name) <> ''),
    -- 复核字段成对出现；闭环必须有复核（阶段 1 先复核、阶段 2 再闭环）。
    CONSTRAINT ck_adverse_rca_review CHECK (
        (reviewer_id IS NULL AND reviewer_name IS NULL AND reviewed_at IS NULL
            AND review_conclusion IS NULL)
        OR (reviewer_id IS NOT NULL AND btrim(reviewer_name) <> ''
            AND reviewed_at IS NOT NULL AND btrim(review_conclusion) <> '')
    ),
    CONSTRAINT ck_adverse_rca_closed CHECK (
        (status = 'OPEN' AND closed_at IS NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE INDEX idx_adverse_rca_event ON m4_qc.adverse_rca (event_id);
CREATE INDEX idx_adverse_rca_status ON m4_qc.adverse_rca (status);
CREATE INDEX idx_adverse_rca_due_date ON m4_qc.adverse_rca (due_date);

COMMENT ON TABLE m4_qc.adverse_rca IS
    'RCA 记录（append-only）:时间线/根因/整改/责任人/期限；复核与闭环字段一次性写入，一条事件一条 RCA';
COMMENT ON COLUMN m4_qc.adverse_rca.event_id IS '关联不良事件标识（FK；一条事件只允许一条 RCA）';
COMMENT ON COLUMN m4_qc.adverse_rca.event_no IS '关联事件号快照';
COMMENT ON COLUMN m4_qc.adverse_rca.timeline IS '事件时间线 jsonb 数组（1–20 条，含 occurredAt/description 脱敏文本）';
COMMENT ON COLUMN m4_qc.adverse_rca.root_cause IS '根因分析（≤500 字；导出不输出原文）';
COMMENT ON COLUMN m4_qc.adverse_rca.corrective_action IS '整改措施（≤500 字；导出不输出原文）';
COMMENT ON COLUMN m4_qc.adverse_rca.responsible_name IS '整改责任人姓名摘要（≤64，非患者信息）';
COMMENT ON COLUMN m4_qc.adverse_rca.due_date IS '整改期限（本地日期，不早于事件发生日）';
COMMENT ON COLUMN m4_qc.adverse_rca.status IS 'RCA 状态：OPEN=未闭环、CLOSED=已闭环';
COMMENT ON COLUMN m4_qc.adverse_rca.reviewer_id IS '复核人用户标识（阶段 1 写入，独立复核）';
COMMENT ON COLUMN m4_qc.adverse_rca.reviewer_name IS '复核人姓名摘要（≤64）';
COMMENT ON COLUMN m4_qc.adverse_rca.reviewed_at IS '复核时间（阶段 1）';
COMMENT ON COLUMN m4_qc.adverse_rca.review_conclusion IS '复核/闭环结论（≤300 字；导出不输出原文）';
COMMENT ON COLUMN m4_qc.adverse_rca.closed_at IS '闭环时间（阶段 2）';

-- RCA 守卫：内容不可变；复核/闭环字段只能从 NULL 一次性写入；状态只允许 OPEN→CLOSED。
CREATE OR REPLACE FUNCTION m4_qc.fn_adverse_rca_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.event_no IS DISTINCT FROM OLD.event_no
       OR NEW.timeline IS DISTINCT FROM OLD.timeline
       OR NEW.root_cause IS DISTINCT FROM OLD.root_cause
       OR NEW.corrective_action IS DISTINCT FROM OLD.corrective_action
       OR NEW.responsible_name IS DISTINCT FROM OLD.responsible_name
       OR NEW.due_date IS DISTINCT FROM OLD.due_date
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_qc.adverse_rca content columns are immutable'
            USING ERRCODE = '55000',
                  HINT = 'RCA 内容 append-only；更正请新建 RCA 流程（本期不提供修改端点）';
    END IF;

    IF OLD.reviewed_at IS NOT NULL AND (
        NEW.reviewer_id IS DISTINCT FROM OLD.reviewer_id
        OR NEW.reviewer_name IS DISTINCT FROM OLD.reviewer_name
        OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
        OR NEW.review_conclusion IS DISTINCT FROM OLD.review_conclusion) THEN
        RAISE EXCEPTION 'm4_qc.adverse_rca review fields are write-once'
            USING ERRCODE = '55000',
                  HINT = '复核字段写入后不可改；如需变更请走数据修复流程';
    END IF;

    IF OLD.closed_at IS NOT NULL AND NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
        RAISE EXCEPTION 'm4_qc.adverse_rca closed_at is write-once'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'OPEN' AND NEW.status = 'CLOSED') THEN
        RAISE EXCEPTION 'm4_qc.adverse_rca illegal status transition: % -> %',
            OLD.status, NEW.status
            USING ERRCODE = '55000',
                  HINT = 'RCA 只允许 OPEN→CLOSED；不得回退或重复闭环';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_qc.fn_adverse_rca_guard() IS
    'RCA 守卫：内容不可变、复核/闭环字段一次性写入、状态只允许 OPEN→CLOSED（55000）';

CREATE TRIGGER trg_adverse_rca_guard
    BEFORE UPDATE ON m4_qc.adverse_rca
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_adverse_rca_guard();
CREATE TRIGGER trg_adverse_rca_no_delete
    BEFORE DELETE ON m4_qc.adverse_rca
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_reject_mutation();
CREATE TRIGGER trg_adverse_rca_no_truncate
    BEFORE TRUNCATE ON m4_qc.adverse_rca
    FOR EACH STATEMENT EXECUTE FUNCTION m4_qc.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 5. chart_review 病历质量抽查
-- -----------------------------------------------------------------------------
CREATE TABLE m4_qc.chart_review (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    review_no     varchar(32)   NOT NULL,
    schedule_id   uuid,
    record_id     uuid,
    dept_id       uuid          NOT NULL,
    dept_name     varchar(120),
    reviewer_id   uuid,
    reviewer_name varchar(64)   NOT NULL,
    reviewed_at   timestamptz   NOT NULL,
    items         jsonb         NOT NULL,
    total_score   integer       NOT NULL,
    max_score     integer       NOT NULL,
    grade         varchar(1)    NOT NULL,
    defect_count  integer       NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'OPEN',
    version       integer       NOT NULL DEFAULT 1,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    uuid,
    CONSTRAINT pk_chart_review PRIMARY KEY (id),
    CONSTRAINT uk_chart_review_no UNIQUE (review_no),
    CONSTRAINT ck_chart_review_items CHECK (
        jsonb_typeof(items) = 'array' AND jsonb_array_length(items) BETWEEN 1 AND 12
    ),
    CONSTRAINT ck_chart_review_scores CHECK (
        max_score >= 1 AND total_score >= 0 AND total_score <= max_score
    ),
    CONSTRAINT ck_chart_review_grade CHECK (grade IN ('A', 'B', 'C', 'D')),
    CONSTRAINT ck_chart_review_defect_count CHECK (defect_count >= 0),
    CONSTRAINT ck_chart_review_status CHECK (status IN ('OPEN', 'RECTIFYING', 'CLOSED')),
    CONSTRAINT ck_chart_review_version CHECK (version >= 1),
    CONSTRAINT ck_chart_review_reviewer_not_blank CHECK (btrim(reviewer_name) <> '')
);

CREATE INDEX idx_chart_review_reviewed_at ON m4_qc.chart_review (reviewed_at);
CREATE INDEX idx_chart_review_status ON m4_qc.chart_review (status);
CREATE INDEX idx_chart_review_dept ON m4_qc.chart_review (dept_id);
CREATE INDEX idx_chart_review_schedule ON m4_qc.chart_review (schedule_id);

COMMENT ON TABLE m4_qc.chart_review IS
    '病历质量抽查：评分项 jsonb（完整性/时效性/签名/逻辑一致性）、总分/满分/等级/缺陷计数；状态 OPEN→RECTIFYING→CLOSED';
COMMENT ON COLUMN m4_qc.chart_review.review_no IS '抽查号（CR-<yyyyMMdd>-<8 位十六进制>，服务端生成、唯一）';
COMMENT ON COLUMN m4_qc.chart_review.schedule_id IS '关联台次标识（逻辑关联，可空）';
COMMENT ON COLUMN m4_qc.chart_review.record_id IS '关联麻醉记录单标识（逻辑关联，可空）';
COMMENT ON COLUMN m4_qc.chart_review.dept_id IS '科室标识（数据范围过滤依据）';
COMMENT ON COLUMN m4_qc.chart_review.dept_name IS '科室名称快照';
COMMENT ON COLUMN m4_qc.chart_review.reviewer_id IS '抽查人用户标识（服务端按调用者固化）';
COMMENT ON COLUMN m4_qc.chart_review.reviewer_name IS '抽查人姓名摘要（≤64）';
COMMENT ON COLUMN m4_qc.chart_review.reviewed_at IS '抽查时间';
COMMENT ON COLUMN m4_qc.chart_review.items IS
    '评分项 jsonb 数组（itemCode/score/maxScore/defectNote；脱敏、≤12 项、itemCode 不重复由服务层校验）';
COMMENT ON COLUMN m4_qc.chart_review.total_score IS '总分（Σ score，服务端计算，不接受客户端传入）';
COMMENT ON COLUMN m4_qc.chart_review.max_score IS '满分（Σ maxScore，服务端计算）';
COMMENT ON COLUMN m4_qc.chart_review.grade IS '等级：A≥95%、B≥90%、C≥80%、D<80%（合格线 90%【待确认】）';
COMMENT ON COLUMN m4_qc.chart_review.defect_count IS '缺陷项数（score < maxScore 的项数，服务端计算）';
COMMENT ON COLUMN m4_qc.chart_review.status IS '状态：OPEN=待整改、RECTIFYING=整改中、CLOSED=已闭环';
COMMENT ON COLUMN m4_qc.chart_review.version IS '版本号（每次状态前移 +1）';

-- 抽查守卫：内容/评分不可变；状态只允许 OPEN→RECTIFYING→CLOSED；version +1。
CREATE OR REPLACE FUNCTION m4_qc.fn_chart_review_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.review_no IS DISTINCT FROM OLD.review_no
       OR NEW.schedule_id IS DISTINCT FROM OLD.schedule_id
       OR NEW.record_id IS DISTINCT FROM OLD.record_id
       OR NEW.dept_id IS DISTINCT FROM OLD.dept_id
       OR NEW.dept_name IS DISTINCT FROM OLD.dept_name
       OR NEW.reviewer_id IS DISTINCT FROM OLD.reviewer_id
       OR NEW.reviewer_name IS DISTINCT FROM OLD.reviewer_name
       OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
       OR NEW.items IS DISTINCT FROM OLD.items
       OR NEW.total_score IS DISTINCT FROM OLD.total_score
       OR NEW.max_score IS DISTINCT FROM OLD.max_score
       OR NEW.grade IS DISTINCT FROM OLD.grade
       OR NEW.defect_count IS DISTINCT FROM OLD.defect_count
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_qc.chart_review scoring columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '抽查评分 append-only；需要重新评分请新建抽查记录';
    END IF;

    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'm4_qc.chart_review version must increase by 1: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000';
    END IF;

    IF NOT (
        (OLD.status = 'OPEN' AND NEW.status = 'RECTIFYING')
        OR (OLD.status = 'RECTIFYING' AND NEW.status = 'CLOSED')
    ) THEN
        RAISE EXCEPTION 'm4_qc.chart_review illegal status transition: % -> %',
            OLD.status, NEW.status
            USING ERRCODE = '55000',
                  HINT = '抽查状态只允许 OPEN→RECTIFYING→CLOSED';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_qc.fn_chart_review_guard() IS
    '抽查守卫：评分不可变、状态逐级前进、version +1（55000）';

CREATE TRIGGER trg_chart_review_guard
    BEFORE UPDATE ON m4_qc.chart_review
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_chart_review_guard();
CREATE TRIGGER trg_chart_review_no_delete
    BEFORE DELETE ON m4_qc.chart_review
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_reject_mutation();
CREATE TRIGGER trg_chart_review_no_truncate
    BEFORE TRUNCATE ON m4_qc.chart_review
    FOR EACH STATEMENT EXECUTE FUNCTION m4_qc.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 6. rectification 整改记录（append-only 内容；复核/闭环一次性写入）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_qc.rectification (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    review_id         uuid         NOT NULL,
    review_no         varchar(32)  NOT NULL,
    corrective_action varchar(500) NOT NULL,
    responsible_name  varchar(64)  NOT NULL,
    due_date          date         NOT NULL,
    status            varchar(16)  NOT NULL DEFAULT 'OPEN',
    reviewer_id       uuid,
    reviewer_name     varchar(64),
    reviewed_at       timestamptz,
    review_conclusion varchar(300),
    closed_at         timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT pk_rectification PRIMARY KEY (id),
    CONSTRAINT fk_rectification_review FOREIGN KEY (review_id)
        REFERENCES m4_qc.chart_review (id) ON DELETE RESTRICT,
    CONSTRAINT uk_rectification_review UNIQUE (review_id),
    CONSTRAINT ck_rectification_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_rectification_action_not_blank CHECK (btrim(corrective_action) <> ''),
    CONSTRAINT ck_rectification_responsible_not_blank CHECK (btrim(responsible_name) <> ''),
    CONSTRAINT ck_rectification_review CHECK (
        (reviewer_id IS NULL AND reviewer_name IS NULL AND reviewed_at IS NULL
            AND review_conclusion IS NULL)
        OR (reviewer_id IS NOT NULL AND btrim(reviewer_name) <> ''
            AND reviewed_at IS NOT NULL AND btrim(review_conclusion) <> '')
    ),
    CONSTRAINT ck_rectification_closed CHECK (
        (status = 'OPEN' AND closed_at IS NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

CREATE INDEX idx_rectification_review ON m4_qc.rectification (review_id);
CREATE INDEX idx_rectification_status ON m4_qc.rectification (status);
CREATE INDEX idx_rectification_due_date ON m4_qc.rectification (due_date);

COMMENT ON TABLE m4_qc.rectification IS
    '整改记录（append-only 内容）：整改措施/责任人/期限；复核与闭环字段一次性写入，一条抽查一条整改';
COMMENT ON COLUMN m4_qc.rectification.review_id IS '关联抽查标识（FK；一条抽查只允许一条整改）';
COMMENT ON COLUMN m4_qc.rectification.review_no IS '关联抽查号快照';
COMMENT ON COLUMN m4_qc.rectification.corrective_action IS '整改措施（≤500 字；导出不输出原文）';
COMMENT ON COLUMN m4_qc.rectification.responsible_name IS '整改责任人姓名摘要（≤64）';
COMMENT ON COLUMN m4_qc.rectification.due_date IS '整改期限（本地日期，不早于抽查日）';
COMMENT ON COLUMN m4_qc.rectification.status IS '整改状态：OPEN=未闭环、CLOSED=已闭环';
COMMENT ON COLUMN m4_qc.rectification.reviewer_id IS '复核人用户标识（独立复核）';
COMMENT ON COLUMN m4_qc.rectification.reviewer_name IS '复核人姓名摘要（≤64）';
COMMENT ON COLUMN m4_qc.rectification.reviewed_at IS '复核时间';
COMMENT ON COLUMN m4_qc.rectification.review_conclusion IS '闭环结论（≤300 字；导出不输出原文）';
COMMENT ON COLUMN m4_qc.rectification.closed_at IS '闭环时间';

CREATE OR REPLACE FUNCTION m4_qc.fn_rectification_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.review_id IS DISTINCT FROM OLD.review_id
       OR NEW.review_no IS DISTINCT FROM OLD.review_no
       OR NEW.corrective_action IS DISTINCT FROM OLD.corrective_action
       OR NEW.responsible_name IS DISTINCT FROM OLD.responsible_name
       OR NEW.due_date IS DISTINCT FROM OLD.due_date
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_qc.rectification content columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '整改内容 append-only；更正请新建整改流程（本期不提供修改端点）';
    END IF;

    IF OLD.reviewed_at IS NOT NULL AND (
        NEW.reviewer_id IS DISTINCT FROM OLD.reviewer_id
        OR NEW.reviewer_name IS DISTINCT FROM OLD.reviewer_name
        OR NEW.reviewed_at IS DISTINCT FROM OLD.reviewed_at
        OR NEW.review_conclusion IS DISTINCT FROM OLD.review_conclusion) THEN
        RAISE EXCEPTION 'm4_qc.rectification review fields are write-once'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT (OLD.status = 'OPEN' AND NEW.status = 'CLOSED') THEN
        RAISE EXCEPTION 'm4_qc.rectification illegal status transition: % -> %',
            OLD.status, NEW.status
            USING ERRCODE = '55000',
                  HINT = '整改只允许 OPEN→CLOSED；不得回退或重复闭环';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_qc.fn_rectification_guard() IS
    '整改守卫：内容不可变、复核/闭环字段一次性写入、状态只允许 OPEN→CLOSED（55000）';

CREATE TRIGGER trg_rectification_guard
    BEFORE UPDATE ON m4_qc.rectification
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_rectification_guard();
CREATE TRIGGER trg_rectification_no_delete
    BEFORE DELETE ON m4_qc.rectification
    FOR EACH ROW EXECUTE FUNCTION m4_qc.fn_reject_mutation();
CREATE TRIGGER trg_rectification_no_truncate
    BEFORE TRUNCATE ON m4_qc.rectification
    FOR EACH STATEMENT EXECUTE FUNCTION m4_qc.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 7. 权限点（m4:qc:read|write|review|export）与最小授予
--    read   GET 指标/结果/台账；write  登记事件/RCA/抽查/整改；
--    review 复核与闭环（RCA/整改）；export 上报导出（另写审计 QC_EXPORT）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000079', 'm4:qc:read',
     'M4 质控指标/不良事件/病历抽查只读', 'API', '/api/v1/qc/*', 'GET', NULL, 148),
    ('b0000000-0000-4000-8000-00000000007a', 'm4:qc:write',
     'M4 不良事件登记/RCA/病历抽查与整改', 'API', '/api/v1/qc/*', 'POST', NULL, 149),
    ('b0000000-0000-4000-8000-00000000007b', 'm4:qc:review',
     'M4 质控复核与闭环', 'API', '/api/v1/qc/*', 'POST', NULL, 150),
    ('b0000000-0000-4000-8000-00000000007c', 'm4:qc:export',
     'M4 质控上报导出', 'API', '/api/v1/qc/export', 'GET', NULL, 151)
ON CONFLICT DO NOTHING;

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：全院质控全部能力（初始化/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000079'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000007a'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000007b'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000007c'),
    -- SYS_QC_MANAGER（质控专员）：指标复核、事件闭环与上报导出
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-000000000079'),
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-00000000007a'),
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-00000000007b'),
    ('a0000000-0000-4000-8000-000000000006', 'b0000000-0000-4000-8000-00000000007c'),
    -- SYS_OR_MANAGER（DEPT）：本科室登记与抽查；复核/导出归质控专员与管理员
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000079'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-00000000007a')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 8. 合成参考指标（QC-SYN-*，provisional=true）
--    这些是**目录骨架与演示口径**，不是国家/省级正式指标；正式目录【待确认】（ADR-0022），
--    确认后以新版本行替换（code 不变、version 递增），不原地修改本批行。
-- -----------------------------------------------------------------------------
INSERT INTO m4_qc.qc_indicator (
    id, code, name, category, definition, data_source, numerator_label, denominator_label,
    unit, threshold_operator, threshold_value, version, effective_from, effective_to, status,
    provisional, in_target_statistics)
VALUES
    ('7c000000-0000-4000-8000-000000000001', 'QC-SYN-STRUCT-001', '麻醉记录单完成率', 'STRUCTURE',
     '区间内已完成（有结束时间）的麻醉记录单台次 / 同期非取消台次 × 100%【合成参考口径，待院方确认】',
     'M3 麻醉记录单（m2_anes.anesthesia_record）+ M2 排程台次（m1_sched.schedule）',
     '已完成麻醉记录单的台次数', '同期非取消台次数', 'PERCENT', 'GTE', 95.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, true),
    ('7c000000-0000-4000-8000-000000000002', 'QC-SYN-PROC-002', '术前访视完成率', 'PROCESS',
     '区间内已完成术前访视的台次 / 同期非取消台次 × 100%【合成参考口径，待院方确认】',
     'M3 术前访视记录（m2_anes.preop_assessment）+ M2 排程台次（m1_sched.schedule）',
     '有术前访视记录的台次数', '同期非取消台次数', 'PERCENT', 'GTE', 95.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, true),
    ('7c000000-0000-4000-8000-000000000003', 'QC-SYN-PROC-003', '三方核查完成率', 'PROCESS',
     '区间内完成 Time-out 与 Sign-out 的台次 / 同期非取消台次 × 100%【合成参考口径，待院方确认】',
     'M3 三方核查（m2_anes.three_way_check）+ M2 排程台次（m1_sched.schedule）',
     '完成 Time-out 与 Sign-out 的台次数', '同期非取消台次数', 'PERCENT', 'GTE', 95.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, true),
    ('7c000000-0000-4000-8000-000000000004', 'QC-SYN-PROC-004', '麻精专册双人签字结构合规率',
     'STRUCTURE',
     '区间内麻精专册登记中双人签字字段完整（两名不同签字人且姓名非空）的条数 / 同期登记条数 × 100%【结构合规口径：仅衡量登记字段完整性，不代表术中实时双人核对行为；Q-1 确认前不计入达标统计】',
     'M4 麻精专册（m4_drug.narcotic_register）',
     '双人签字字段完整的登记条数', '同期麻精专册登记条数', 'PERCENT', 'GTE', 100.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, false),
    ('7c000000-0000-4000-8000-000000000005', 'QC-SYN-OUT-005', '不良事件上报率', 'OUTCOME',
     '区间内上报的不良事件数 / 同期非取消台次 × 1000‰【合成参考口径：上报率高低与报告文化相关，阈值待院方确认】',
     'M4 质控不良事件（m4_qc.adverse_event）+ M2 排程台次（m1_sched.schedule）',
     '区间内上报的不良事件数', '同期非取消台次数', 'PER_1000_CASES', 'GTE', 3.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, true),
    ('7c000000-0000-4000-8000-000000000006', 'QC-SYN-PROC-006', '病历质量抽查合格率', 'PROCESS',
     '区间内抽查得分率 ≥ 90% 的记录数 / 同期抽查记录数 × 100%【合格线待院方确认】',
     'M4 质控病历抽查（m4_qc.chart_review）',
     '得分率 ≥ 90% 的抽查记录数', '同期病历抽查记录数', 'PERCENT', 'GTE', 90.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, true),
    ('7c000000-0000-4000-8000-000000000007', 'QC-SYN-STRUCT-007', '高值耗材 UDI 使用登记率', 'STRUCTURE',
     '区间内高值耗材出库流水中带 UDI 编码的条数 / 同期高值耗材出库流水条数 × 100%【高值口径与批次策略待院方确认】',
     'M4 耗材出入库流水（m4_material.stock_movement）+ 耗材主数据（m4_material.material）',
     '带 UDI 编码的高值耗材出库条数', '同期高值耗材出库流水条数', 'PERCENT', 'GTE', 100.00, '1.0.0',
     DATE '2026-01-01', NULL, 'ACTIVE', true, true)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- 9. 迁移自检（fail-fast：表/权限/授权/参考指标必须齐全，避免半套上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_tables integer;
    missing_permissions integer;
    missing_grants integer;
    indicator_count integer;
BEGIN
    SELECT count(*) INTO missing_tables
      FROM (VALUES ('qc_indicator'), ('qc_indicator_result'), ('adverse_event'),
                   ('adverse_rca'), ('chart_review'), ('rectification')) AS t(name)
     WHERE NOT EXISTS (
         SELECT 1 FROM information_schema.tables
          WHERE table_schema = 'm4_qc' AND table_name = t.name
     );
    IF missing_tables > 0 THEN
        RAISE EXCEPTION 'V25 自检失败：缺少 % 张 m4_qc 表', missing_tables;
    END IF;

    SELECT count(*) INTO missing_permissions
      FROM (VALUES ('m4:qc:read'), ('m4:qc:write'), ('m4:qc:review'), ('m4:qc:export')) AS p(code)
     WHERE NOT EXISTS (
         SELECT 1 FROM m0_platform.sys_permission sp WHERE sp.code = p.code
     );
    IF missing_permissions > 0 THEN
        RAISE EXCEPTION 'V25 自检失败：缺少 % 个权限点', missing_permissions;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm4:qc:read'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:qc:write'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:qc:review'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:qc:export'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:qc:read'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:qc:write'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:qc:review'),
            ('a0000000-0000-4000-8000-000000000006', 'm4:qc:export'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:qc:read'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:qc:write')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V25 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;

    SELECT count(*) INTO indicator_count
      FROM m4_qc.qc_indicator WHERE code LIKE 'QC-SYN-%' AND status = 'ACTIVE';
    IF indicator_count < 7 THEN
        RAISE EXCEPTION 'V25 自检失败：合成参考指标不足（当前 % 条，期望 >= 7）', indicator_count;
    END IF;
END;
$$;
