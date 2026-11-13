-- =============================================================================
-- ANESOP 数据库迁移 V15：M2 术中预警规则、告警留痕与抢救记录（M3-M2-02）
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 设计依据：
--   方案书 §2.4.3（术中预警：阈值/持续时间/规则版本、告警确认与忽略留痕；抢救记录：
--   人员/用药/事件，临床记录不可篡改）、§3.4.2（麻醉记录单/事件实体）
--   AGENTS.md（临床规则属高风险代码；临床阈值须临床顾问确认，未确认标【待确认】；
--   记录不可变、审计无 PHI；迁移 expand-contract + 回滚说明）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.6.0（additive）
--   任务卡：docs/ai/tasks/M3-backlog.md M3-M2-02
--
-- 内容（expand：只新增表/索引/权限/种子，不改写 V1–V14 既有列、约束与数据）：
--   1) m2_anes.alert_rule    预警规则版本行：一个 code 有多个 version，仅一个启用；
--                            阈值/持续时长/严重度可配置，规则不可原地修改（版本化）。
--   2) m2_anes.alert         告警留痕：触发即落库（rule 编码/版本快照），只允许一次
--                            TRIGGERED → ACKNOWLEDGED/IGNORED 流转；去重键唯一。
--   3) m2_anes.resuscitation_record 抢救记录：append-only（UPDATE/DELETE/TRUNCATE 拒绝，
--                            SQLSTATE 55000），时间窗落在麻醉记录单时间窗内由服务层校验。
--   4) 权限点 m2:alert:read|ack|configure、m2:rescue:write（最小授权：configure 仅 SYS_ADMIN）。
--   5) 默认规则种子（SYNTHETIC，阈值全部标【待确认】，不得视为临床决策依据）。
--
-- 规则版本化与不可变（临床规则高风险）：
--   - 保存规则 = 在同一 code 下生成 version+1 的新行，并把旧行 enabled 置 false；
--     历史版本与阈值永不被改写（触发器拒绝改 code/version/阈值/re- enable）。
--   - 部分唯一索引 uk_alert_rule_enabled_code 保证同一 code 最多一个启用版本；
--   - 告警行保存触发时的 rule_id + rule_code + rule_version 快照，规则后续升级不追溯
--     修改历史告警（临床留痕可追溯“当时依据哪一版规则”）。
--
-- 告警去重（幂等）：
--   dedupe_key = recordId + ':' + ruleCode + ':' + 越界窗口起始秒（epoch 秒），唯一索引
--   uk_alert_dedupe 兜底；重复评估同一越界窗口不重复插入；处置完成后再次进入新的越界
--   窗口（窗口起始不同）会生成新告警，符合“持续/复发”语义。
--
-- 阈值口径（【待确认】）：
--   LT           value < threshold_low  触发（等于阈值不触发）
--   GT           value > threshold_high 触发（等于阈值不触发）
--   OUT_OF_RANGE value < threshold_low 或 value > threshold_high 触发（区间闭合处不触发）
--   duration_seconds 为“连续越界达到该秒数才触发”，0 表示首个越界采样即触发；
--   采样间隔超过 anesop.m2.alert.max-sample-gap-seconds（默认 300s）视为新窗口。
--   MAP = nibp_dia + (nibp_sys - nibp_dia) / 3（V4 只存收缩/舒张压，MAP 为服务端推导）。
--
-- 临床安全约定：
--   - 默认规则与阈值仅演示，必须由临床顾问确认后由管理员通过 PUT 规则接口改版；
--   - 告警只做提示，不替代临床判断（AGENTS H-06），不触发任何自动给药/自动处置；
--   - 审计 ALERT_TRIGGERED/ALERT_ACK/RESCUE_RECORD 只含标识、规则编码/版本、指标与数值，
--     不含患者姓名/证件号/住院号/手机号，也不含处理原因与事件摘要原文；
--   - 抢救记录 event_summary 为结构化 jsonb（对象），drugs 为 jsonb 数组；表内不存患者标识。
--
-- expand-contract 说明：
--   expand   只新增三表、索引、权限与 5 条 SYNTHETIC 规则种子；旧代码（无预警/抢救）
--            完全不受影响，V13 记录单与 V4 体征表语义不变。
--   migrate  规则/告警/抢救记录并行积累；告警评估为查询时/手动评估（见
--            docs/modules/m2/README.md），不引入定时任务。
--   contract 删列/改类型/改语义必须新版本 + 双写 + ADR + 回滚演练；本脚本合入后禁止修改
--            （Flyway checksum），修正一律新增 V<N+1>。
--
-- 迁移纪律：本脚本已合入后禁止修改。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.10）：
--   1) 应用回滚：下线 /v1/anesthesia/alert-rules*、/records/{id}/alerts*、
--      /alerts/{id}/acknowledge、/records/{id}/resuscitations 路由与前端告警/抢救面板；
--      记录单、时间轴、用药、出入量与采集不受影响。
--   2) 库层前向修复（需 DBA 审批 + 备份，顺序不可颠倒）：
--      DROP TABLE m2_anes.resuscitation_record;
--      DROP TABLE m2_anes.alert;
--      DROP TABLE m2_anes.alert_rule;
--      DROP FUNCTION IF EXISTS m2_anes.fn_resuscitation_record_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_alert_guard();
--      DROP FUNCTION IF EXISTS m2_anes.fn_alert_rule_guard();
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000051',
--                                'b0000000-0000-4000-8000-000000000052',
--                                'b0000000-0000-4000-8000-000000000053',
--                                'b0000000-0000-4000-8000-000000000054');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:alert:read', 'm2:alert:ack', 'm2:alert:configure',
--                       'm2:rescue:write');
--   3) 回滚前导出三张表并校验行数；告警与抢救记录属临床留痕，须先归档到离线审计库，
--      生产执行须人签字。V15 不修改 V13/V14 表，可与二者独立回滚（先删本表再回滚 V13）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. alert_rule 预警规则（版本化；同一 code 仅一个启用版本）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.alert_rule (
    id               uuid          NOT NULL DEFAULT gen_random_uuid(),
    code             varchar(64)   NOT NULL,
    name             varchar(200)  NOT NULL,
    metric           varchar(16)   NOT NULL,
    operator         varchar(16)   NOT NULL,
    threshold_low    numeric(8, 2),
    threshold_high   numeric(8, 2),
    duration_seconds integer       NOT NULL DEFAULT 0,
    severity         varchar(16)   NOT NULL,
    version          integer       NOT NULL DEFAULT 1,
    enabled          boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       uuid,
    CONSTRAINT pk_alert_rule PRIMARY KEY (id),
    CONSTRAINT uk_alert_rule_code_version UNIQUE (code, version),
    CONSTRAINT ck_alert_rule_code CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_alert_rule_metric
        CHECK (metric IN ('HR', 'SPO2', 'MAP', 'ETCO2', 'TEMP')),
    CONSTRAINT ck_alert_rule_operator
        CHECK (operator IN ('LT', 'GT', 'OUT_OF_RANGE')),
    CONSTRAINT ck_alert_rule_severity
        CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT ck_alert_rule_duration
        CHECK (duration_seconds BETWEEN 0 AND 3600),
    CONSTRAINT ck_alert_rule_version CHECK (version >= 1),
    -- 算子与阈值自洽（服务层先返回 400，数据库为第二道防线）。
    CONSTRAINT ck_alert_rule_thresholds CHECK (
        (operator = 'LT' AND threshold_low IS NOT NULL AND threshold_high IS NULL)
        OR (operator = 'GT' AND threshold_high IS NOT NULL AND threshold_low IS NULL)
        OR (operator = 'OUT_OF_RANGE' AND threshold_low IS NOT NULL
            AND threshold_high IS NOT NULL AND threshold_low < threshold_high)
    )
);

-- 同一规则编码最多一个启用版本（并发改版时第二道防线：旧版本先停用再插新版本）。
CREATE UNIQUE INDEX uk_alert_rule_enabled_code
    ON m2_anes.alert_rule (code)
    WHERE enabled = true;
CREATE INDEX idx_alert_rule_code_version
    ON m2_anes.alert_rule (code, version DESC);

COMMENT ON TABLE m2_anes.alert_rule IS
    '术中预警规则（版本化）：一个 code 多个 version，仅一个 enabled；保存规则=生成新版本并停用旧版本；'
    '默认种子阈值为【待确认】演示口径，须临床顾问确认';
COMMENT ON COLUMN m2_anes.alert_rule.id IS '规则版本行标识（UUID）';
COMMENT ON COLUMN m2_anes.alert_rule.code IS
    '规则编码（稳定业务键，[A-Z][A-Z0-9_]），如 SPO2_LOW/HR_LOW/HR_HIGH/MAP_LOW/ETCO2_RANGE';
COMMENT ON COLUMN m2_anes.alert_rule.name IS '规则中文名称';
COMMENT ON COLUMN m2_anes.alert_rule.metric IS
    '评估指标：HR/SPO2/MAP/ETCO2/TEMP；MAP 由无创血压收缩/舒张压推导（dia+(sys-dia)/3）';
COMMENT ON COLUMN m2_anes.alert_rule.operator IS
    '比较算子：LT=低于下限，GT=高于上限，OUT_OF_RANGE=超出区间；等于阈值不触发';
COMMENT ON COLUMN m2_anes.alert_rule.threshold_low IS '下限阈值（LT/OUT_OF_RANGE 必填；其余为空）';
COMMENT ON COLUMN m2_anes.alert_rule.threshold_high IS '上限阈值（GT/OUT_OF_RANGE 必填；其余为空）';
COMMENT ON COLUMN m2_anes.alert_rule.duration_seconds IS
    '连续越界持续秒数（0–3600，0=首个越界采样即触发）；采样间隔超阈值视为新窗口';
COMMENT ON COLUMN m2_anes.alert_rule.severity IS '严重度：INFO/WARN/CRITICAL';
COMMENT ON COLUMN m2_anes.alert_rule.version IS
    '规则版本号（从 1 起，只增）；同时作为 PUT 的乐观锁版本，过期 409';
COMMENT ON COLUMN m2_anes.alert_rule.enabled IS
    '是否启用；停用同样生成新版本（旧版本 enabled 置 false 后不可再启用）';
COMMENT ON COLUMN m2_anes.alert_rule.created_at IS '该版本创建时间（服务端时间）';
COMMENT ON COLUMN m2_anes.alert_rule.created_by IS '该版本创建人用户标识；种子为 NULL（系统）';
COMMENT ON COLUMN m2_anes.alert_rule.updated_at IS '最后更新时间（服务端时间）';
COMMENT ON COLUMN m2_anes.alert_rule.updated_by IS '最后更新人用户标识';

-- 规则守卫：历史版本不可改写。
--   a) INSERT 允许（新版本）；
--   b) UPDATE 只允许“启用→停用”并推进 updated_at/updated_by：不得改 code/version/名称/
--      指标/算子/阈值/时长/严重度/创建留痕；已停用的版本不得再改（禁止 re-enable）；
--   c) DELETE/TRUNCATE 拒绝（规则历史属临床配置留痕）。SQLSTATE 55000。
CREATE OR REPLACE FUNCTION m2_anes.fn_alert_rule_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.code IS DISTINCT FROM OLD.code
           OR NEW.version IS DISTINCT FROM OLD.version
           OR NEW.name IS DISTINCT FROM OLD.name
           OR NEW.metric IS DISTINCT FROM OLD.metric
           OR NEW.operator IS DISTINCT FROM OLD.operator
           OR NEW.threshold_low IS DISTINCT FROM OLD.threshold_low
           OR NEW.threshold_high IS DISTINCT FROM OLD.threshold_high
           OR NEW.duration_seconds IS DISTINCT FROM OLD.duration_seconds
           OR NEW.severity IS DISTINCT FROM OLD.severity
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
            RAISE EXCEPTION
                'm2_anes.alert_rule is versioned: configured columns are immutable'
                USING ERRCODE = '55000',
                      HINT = '规则只能以新版本保存（同一 code 生成 version+1），不得原地修改';
        END IF;
        IF OLD.enabled = false THEN
            RAISE EXCEPTION
                'm2_anes.alert_rule version % of % is already disabled', OLD.version, OLD.code
                USING ERRCODE = '55000',
                      HINT = '历史版本不可重新启用；请保存新版本';
        END IF;
        IF NEW.enabled = true THEN
            RAISE EXCEPTION
                'm2_anes.alert_rule UPDATE may only disable a version'
                USING ERRCODE = '55000',
                      HINT = '启用状态的变更必须保存为新版本（version+1）';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION
        'm2_anes.alert_rule history is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '规则历史不可删除；停用请保存新版本';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_alert_rule_guard() IS
    '规则版本守卫：配置列/版本号不可原地修改，仅允许启用→停用；拒绝 DELETE/TRUNCATE（55000）';

CREATE TRIGGER trg_alert_rule_guard_update
    BEFORE UPDATE ON m2_anes.alert_rule
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_alert_rule_guard();

CREATE TRIGGER trg_alert_rule_guard_delete
    BEFORE DELETE ON m2_anes.alert_rule
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_alert_rule_guard();

CREATE TRIGGER trg_alert_rule_guard_truncate
    BEFORE TRUNCATE ON m2_anes.alert_rule
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_alert_rule_guard();

-- -----------------------------------------------------------------------------
-- 2. alert 告警留痕（append-only + 一次性处置流转）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.alert (
    id                 uuid          NOT NULL DEFAULT gen_random_uuid(),
    record_id          uuid          NOT NULL,
    rule_id            uuid          NOT NULL,
    rule_code          varchar(64)   NOT NULL,
    rule_version       integer       NOT NULL,
    metric             varchar(16)   NOT NULL,
    value              numeric(8, 2) NOT NULL,
    unit               varchar(16),
    severity           varchar(16)   NOT NULL,
    triggered_at       timestamptz   NOT NULL,
    dedupe_key         varchar(160)  NOT NULL,
    status             varchar(16)   NOT NULL DEFAULT 'TRIGGERED',
    acknowledged_by    uuid,
    acknowledged_at    timestamptz,
    acknowledge_reason varchar(500),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    created_by         uuid,
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    updated_by         uuid,
    version            bigint        NOT NULL DEFAULT 1,
    CONSTRAINT pk_alert PRIMARY KEY (id),
    CONSTRAINT fk_alert_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    CONSTRAINT fk_alert_rule FOREIGN KEY (rule_id)
        REFERENCES m2_anes.alert_rule (id) ON DELETE RESTRICT,
    CONSTRAINT uk_alert_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_alert_metric CHECK (metric IN ('HR', 'SPO2', 'MAP', 'ETCO2', 'TEMP')),
    CONSTRAINT ck_alert_severity CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    CONSTRAINT ck_alert_status
        CHECK (status IN ('TRIGGERED', 'ACKNOWLEDGED', 'IGNORED')),
    CONSTRAINT ck_alert_rule_version CHECK (rule_version >= 1),
    CONSTRAINT ck_alert_version CHECK (version >= 1),
    -- 处置留痕三件套必须同时有或同时无：未处置全空，已处置全非空。
    CONSTRAINT ck_alert_ack_fields CHECK (
        (status = 'TRIGGERED' AND acknowledged_by IS NULL
            AND acknowledged_at IS NULL AND acknowledge_reason IS NULL)
        OR (status IN ('ACKNOWLEDGED', 'IGNORED') AND acknowledged_by IS NOT NULL
            AND acknowledged_at IS NOT NULL AND acknowledge_reason IS NOT NULL)
    )
);

CREATE INDEX idx_alert_record_triggered
    ON m2_anes.alert (record_id, triggered_at DESC);
CREATE INDEX idx_alert_status ON m2_anes.alert (status);
CREATE INDEX idx_alert_rule_id ON m2_anes.alert (rule_id);

COMMENT ON TABLE m2_anes.alert IS
    '术中告警留痕：触发即落库（规则编码/版本快照）；不可改删，仅允许 TRIGGERED→ACKNOWLEDGED/IGNORED '
    '一次流转；dedupe_key 唯一保证重复评估不重复插入';
COMMENT ON COLUMN m2_anes.alert.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.alert.record_id IS '所属麻醉记录单（FK；数据范围沿记录单）';
COMMENT ON COLUMN m2_anes.alert.rule_id IS '触发时的规则版本行（FK alert_rule.id）';
COMMENT ON COLUMN m2_anes.alert.rule_code IS '触发时的规则编码快照（规则升级不追溯修改）';
COMMENT ON COLUMN m2_anes.alert.rule_version IS '触发时的规则版本号快照（新告警使用新版本）';
COMMENT ON COLUMN m2_anes.alert.metric IS '触发指标：HR/SPO2/MAP/ETCO2/TEMP';
COMMENT ON COLUMN m2_anes.alert.value IS '触发时实测值（按 metric 单位）';
COMMENT ON COLUMN m2_anes.alert.unit IS '指标单位（bpm/%/mmHg/℃），仅用于展示';
COMMENT ON COLUMN m2_anes.alert.severity IS '严重度：INFO/WARN/CRITICAL（触发时快照）';
COMMENT ON COLUMN m2_anes.alert.triggered_at IS '触发时间（越界持续达到规则时长的那一采样时刻）';
COMMENT ON COLUMN m2_anes.alert.dedupe_key IS
    '去重键 recordId:ruleCode:窗口起始秒（唯一）；重复评估同一窗口不重复插入';
COMMENT ON COLUMN m2_anes.alert.status IS
    '处理状态：TRIGGERED=待处理，ACKNOWLEDGED=已确认，IGNORED=已忽略（只允许一次流转）';
COMMENT ON COLUMN m2_anes.alert.acknowledged_by IS '处理人用户标识；未处理为 NULL';
COMMENT ON COLUMN m2_anes.alert.acknowledged_at IS '处理时间；未处理为 NULL';
COMMENT ON COLUMN m2_anes.alert.acknowledge_reason IS
    '处理原因（临床留痕；不写入日志/审计原文）';
COMMENT ON COLUMN m2_anes.alert.created_at IS '落库时间（服务端时间）';
COMMENT ON COLUMN m2_anes.alert.created_by IS '落库触发人；自动评估为 NULL（系统动作）';
COMMENT ON COLUMN m2_anes.alert.updated_at IS '最后更新时间（服务端时间）';
COMMENT ON COLUMN m2_anes.alert.updated_by IS '最后处理人用户标识；未处理为 NULL';
COMMENT ON COLUMN m2_anes.alert.version IS '乐观锁版本（确认/忽略必须回传，冲突 409）';

-- 告警守卫：
--   a) INSERT 只允许 status=TRIGGERED（告警只能从待处理开始）；
--   b) UPDATE 只允许 TRIGGERED → ACKNOWLEDGED/IGNORED，且只改 status/ack_* /updated_*/version；
--      身份列、规则快照、指标、数值、触发时间、去重键不可改；version 只增；
--   c) DELETE/TRUNCATE 拒绝。SQLSTATE 55000。
CREATE OR REPLACE FUNCTION m2_anes.fn_alert_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status IS DISTINCT FROM 'TRIGGERED' THEN
            RAISE EXCEPTION
                'm2_anes.alert must be inserted as TRIGGERED, got %', NEW.status
                USING ERRCODE = '55000',
                      HINT = '告警只能从待处理状态开始，处理仅允许一次流转';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id
           OR NEW.record_id IS DISTINCT FROM OLD.record_id
           OR NEW.rule_id IS DISTINCT FROM OLD.rule_id
           OR NEW.rule_code IS DISTINCT FROM OLD.rule_code
           OR NEW.rule_version IS DISTINCT FROM OLD.rule_version
           OR NEW.metric IS DISTINCT FROM OLD.metric
           OR NEW.value IS DISTINCT FROM OLD.value
           OR NEW.unit IS DISTINCT FROM OLD.unit
           OR NEW.severity IS DISTINCT FROM OLD.severity
           OR NEW.triggered_at IS DISTINCT FROM OLD.triggered_at
           OR NEW.dedupe_key IS DISTINCT FROM OLD.dedupe_key
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
            RAISE EXCEPTION
                'm2_anes.alert identity/rule snapshot columns are immutable'
                USING ERRCODE = '55000',
                      HINT = '告警留痕不可修改；规则升级不追溯历史告警';
        END IF;
        IF OLD.status IS DISTINCT FROM 'TRIGGERED' THEN
            RAISE EXCEPTION
                'm2_anes.alert % is already %; state is final', OLD.id, OLD.status
                USING ERRCODE = '55000',
                      HINT = '告警只允许一次 TRIGGERED→ACKNOWLEDGED/IGNORED 流转';
        END IF;
        IF NEW.status IS NULL OR NEW.status NOT IN ('ACKNOWLEDGED', 'IGNORED') THEN
            RAISE EXCEPTION
                'm2_anes.alert status may only move to ACKNOWLEDGED or IGNORED'
                USING ERRCODE = '55000',
                      HINT = '待处理告警只能确认或忽略';
        END IF;
        IF NEW.version <= OLD.version THEN
            RAISE EXCEPTION
                'm2_anes.alert version must increase: % -> %', OLD.version, NEW.version
                USING ERRCODE = '55000',
                      HINT = '告警处理必须递增 version（乐观锁）';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION
        'm2_anes.alert is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '告警留痕不可删除；请保留历史';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_alert_guard() IS
    '告警守卫：仅 TRIGGERED 起始、一次性处置流转、快照列不可变、version 只增；拒绝 DELETE/TRUNCATE（55000）';

CREATE TRIGGER trg_alert_guard_insert
    BEFORE INSERT ON m2_anes.alert
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_alert_guard();

CREATE TRIGGER trg_alert_guard_update
    BEFORE UPDATE ON m2_anes.alert
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_alert_guard();

CREATE TRIGGER trg_alert_guard_delete
    BEFORE DELETE ON m2_anes.alert
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_alert_guard();

CREATE TRIGGER trg_alert_guard_truncate
    BEFORE TRUNCATE ON m2_anes.alert
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_alert_guard();

-- -----------------------------------------------------------------------------
-- 3. resuscitation_record 抢救记录（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.resuscitation_record (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    record_id     uuid         NOT NULL,
    resus_type    varchar(32)  NOT NULL,
    started_at    timestamptz  NOT NULL,
    ended_at      timestamptz  NOT NULL,
    event_summary jsonb        NOT NULL DEFAULT '{}'::jsonb,
    drugs         jsonb        NOT NULL DEFAULT '[]'::jsonb,
    outcome       varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    uuid,
    CONSTRAINT pk_resuscitation_record PRIMARY KEY (id),
    CONSTRAINT fk_resuscitation_record_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT,
    CONSTRAINT ck_resuscitation_record_type CHECK (resus_type IN (
        'CPR', 'AIRWAY', 'VASOPRESSOR', 'DEFIBRILLATION', 'OTHER')),
    CONSTRAINT ck_resuscitation_record_outcome
        CHECK (outcome IN ('SUCCESS', 'PARTIAL', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_resuscitation_record_window CHECK (ended_at >= started_at),
    CONSTRAINT ck_resuscitation_record_summary
        CHECK (jsonb_typeof(event_summary) = 'object'),
    CONSTRAINT ck_resuscitation_record_drugs CHECK (jsonb_typeof(drugs) = 'array')
);

CREATE INDEX idx_resuscitation_record_record_started
    ON m2_anes.resuscitation_record (record_id, started_at DESC);
CREATE INDEX idx_resuscitation_record_created_at
    ON m2_anes.resuscitation_record (created_at);

COMMENT ON TABLE m2_anes.resuscitation_record IS
    '抢救记录：append-only（UPDATE/DELETE/TRUNCATE 拒绝，55000）；时间窗必须落在记录单时间窗内；'
    '事件摘要/用药为 jsonb 结构，表内不存患者标识';
COMMENT ON COLUMN m2_anes.resuscitation_record.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.resuscitation_record.record_id IS '所属麻醉记录单（FK；数据范围沿记录单）';
COMMENT ON COLUMN m2_anes.resuscitation_record.resus_type IS
    '抢救类型：CPR/AIRWAY/VASOPRESSOR/DEFIBRILLATION/OTHER（【待确认】临床口径）';
COMMENT ON COLUMN m2_anes.resuscitation_record.started_at IS
    '抢救开始时间（不得早于记录单开始时间；服务层校验 400）';
COMMENT ON COLUMN m2_anes.resuscitation_record.ended_at IS
    '抢救结束时间（>= started_at，不晚于记录结束或当前时间+容差；服务层校验 400）';
COMMENT ON COLUMN m2_anes.resuscitation_record.event_summary IS
    '事件经过摘要 jsonb 对象：{summary, interventions[]}；不得写入患者直接标识';
COMMENT ON COLUMN m2_anes.resuscitation_record.drugs IS
    '抢救用药 jsonb 数组：[{drugName,dose,unit,route,administeredAt}]；≤50 条';
COMMENT ON COLUMN m2_anes.resuscitation_record.outcome IS
    '转归：SUCCESS/PARTIAL/FAILED/UNKNOWN（【待确认】临床口径）';
COMMENT ON COLUMN m2_anes.resuscitation_record.created_at IS '录入时间（服务端时间）';
COMMENT ON COLUMN m2_anes.resuscitation_record.created_by IS '录入人用户标识';

CREATE OR REPLACE FUNCTION m2_anes.fn_resuscitation_record_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.resuscitation_record is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '抢救记录不可修改或删除；更正请新增记录并在事件摘要说明';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_resuscitation_record_append_only() IS
    '抢救记录 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_resuscitation_record_no_update
    BEFORE UPDATE ON m2_anes.resuscitation_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_resuscitation_record_append_only();

CREATE TRIGGER trg_resuscitation_record_no_delete
    BEFORE DELETE ON m2_anes.resuscitation_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_resuscitation_record_append_only();

CREATE TRIGGER trg_resuscitation_record_no_truncate
    BEFORE TRUNCATE ON m2_anes.resuscitation_record
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_resuscitation_record_append_only();

-- -----------------------------------------------------------------------------
-- 4. 权限点（最小授权）
--    m2:alert:read       SYS_ADMIN / SYS_ANESTHESIOLOGIST / SYS_NURSE_ANESTHETIST
--    m2:alert:ack        SYS_ADMIN / SYS_ANESTHESIOLOGIST / SYS_NURSE_ANESTHETIST（临床处置）
--    m2:alert:configure  仅 SYS_ADMIN（信息科代录临床顾问确认后的阈值）
--    m2:rescue:write     SYS_ADMIN / SYS_ANESTHESIOLOGIST / SYS_NURSE_ANESTHETIST
--
-- 为什么 SYS_ADMIN 也持有 ack/rescue：M0 的“禁止越权授权”规则要求操作人只能授予自身
-- 已具备的权限（UserAdminServiceImpl 校验目标角色的全部权限点）；若 SYS_ADMIN 缺少临床角色
-- 的任一权限，管理员将无法创建/更新麻醉医师/麻醉护士账号。故 SYS_ADMIN 作为平台超级权限
-- 持有全部 4 个权限点（与 V13 给 SYS_ADMIN 授 m2:record:read/write 的口径一致）；
-- “configure 仅 SYS_ADMIN”仍是唯一的配置入口约束。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000051', 'm2:alert:read', 'M2 预警规则与告警只读', 'API',
     '/api/v1/anesthesia/alert-rules', 'GET', NULL, 150),
    ('b0000000-0000-4000-8000-000000000052', 'm2:alert:ack', 'M2 告警确认/忽略', 'API',
     '/api/v1/anesthesia/alerts', 'POST', NULL, 151),
    ('b0000000-0000-4000-8000-000000000053', 'm2:alert:configure', 'M2 预警规则配置', 'API',
     '/api/v1/anesthesia/alert-rules', 'PUT', NULL, 152),
    ('b0000000-0000-4000-8000-000000000054', 'm2:rescue:write', 'M2 抢救记录书写', 'API',
     '/api/v1/anesthesia/records', 'POST', NULL, 153);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：规则配置 + 只读 + 处置 + 抢救书写（平台超级权限；M0 越权授权校验要求
    -- 管理员持有目标角色的全部权限，否则无法创建临床账号）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000051'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000052'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000053'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000054'),
    -- SYS_ANESTHESIOLOGIST：读 + 处置 + 抢救记录
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000051'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000052'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000054'),
    -- SYS_NURSE_ANESTHETIST：读 + 处置 + 抢救记录
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000051'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000052'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000054');

-- -----------------------------------------------------------------------------
-- 5. 默认规则种子（SYNTHETIC；阈值全部【待确认】，仅演示引擎与页面，不是临床决策依据）
--    值均可由管理员通过 PUT /v1/anesthesia/alert-rules/{ruleId} 以新版本调整。
-- -----------------------------------------------------------------------------
INSERT INTO m2_anes.alert_rule
    (id, code, name, metric, operator, threshold_low, threshold_high,
     duration_seconds, severity, version, enabled)
VALUES
    ('1b000000-0000-4000-8000-000000000001', 'SPO2_LOW',
     '血氧饱和度过低（默认阈值 90%，【待确认】）', 'SPO2', 'LT', 90.00, NULL, 0, 'CRITICAL', 1, true),
    ('1b000000-0000-4000-8000-000000000002', 'HR_LOW',
     '心动过缓（默认阈值 50 bpm，持续 30s，【待确认】）', 'HR', 'LT', 50.00, NULL, 30, 'WARN', 1, true),
    ('1b000000-0000-4000-8000-000000000003', 'HR_HIGH',
     '心动过速（默认阈值 120 bpm，持续 30s，【待确认】）', 'HR', 'GT', NULL, 120.00, 30, 'WARN', 1, true),
    ('1b000000-0000-4000-8000-000000000004', 'MAP_LOW',
     '平均动脉压过低（默认阈值 65 mmHg，持续 60s，【待确认】）', 'MAP', 'LT', 65.00, NULL, 60,
     'WARN', 1, true),
    ('1b000000-0000-4000-8000-000000000005', 'ETCO2_RANGE',
     '呼气末二氧化碳超出区间（默认 30–50 mmHg，持续 30s，【待确认】）', 'ETCO2',
     'OUT_OF_RANGE', 30.00, 50.00, 30, 'WARN', 1, true);

-- -----------------------------------------------------------------------------
-- 6. 迁移自检（fail fast）
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_alert_rule_code_version'
          AND conrelid = 'm2_anes.alert_rule'::regclass
    ) THEN
        RAISE EXCEPTION 'V15 迁移自检失败：缺少唯一键 uk_alert_rule_code_version';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_alert_dedupe' AND conrelid = 'm2_anes.alert'::regclass
    ) THEN
        RAISE EXCEPTION 'V15 迁移自检失败：缺少唯一键 uk_alert_dedupe';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'm2_anes' AND indexname = 'uk_alert_rule_enabled_code'
    ) THEN
        RAISE EXCEPTION 'V15 迁移自检失败：缺少部分唯一索引 uk_alert_rule_enabled_code';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trg_resuscitation_record_no_update'
          AND tgrelid = 'm2_anes.resuscitation_record'::regclass
          AND NOT tgisinternal
    ) THEN
        RAISE EXCEPTION 'V15 迁移自检失败：缺少抢救记录 append-only 触发器';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_permission
        WHERE code IN ('m2:alert:read', 'm2:alert:ack', 'm2:alert:configure',
                       'm2:rescue:write')) <> 4 THEN
        RAISE EXCEPTION 'V15 迁移自检失败：预警/抢救权限点未全部写入';
    END IF;
    -- configure 仅授 SYS_ADMIN（最小授权）
    IF (SELECT count(DISTINCT role_id) FROM m0_platform.sys_role_permission
        WHERE permission_id = 'b0000000-0000-4000-8000-000000000053') <> 1 THEN
        RAISE EXCEPTION 'V15 迁移自检失败：m2:alert:configure 只允许授予 SYS_ADMIN';
    END IF;
    IF (SELECT count(*) FROM m2_anes.alert_rule WHERE enabled = true) <> 5 THEN
        RAISE EXCEPTION 'V15 迁移自检失败：默认规则种子应为 5 条启用规则';
    END IF;
END
$$;
