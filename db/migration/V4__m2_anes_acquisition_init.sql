-- =============================================================================
-- ANESOP 数据库迁移 V4：M2 麻醉记录 — 设备采集接入（S0-09 POC）
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 设计依据：
--   方案书 §3.6（设备数据采集：五段流水线、时间戳三条规则、离线与数据质量）
--   方案书 §3.4.2（核心实体）、§3.4.3（数据库设计策略）
--   AGENTS.md 铁律 4（采集解耦：断网缓存 ≥4h、幂等补传、设备只采集不控制）
--
-- 关键约定（与 V1 保持一致）：
--   1) 表名单数、字段小写下划线；唯一索引 uk_<表>_<字段>，普通索引 idx_<表>_<字段>。
--   2) 所有时间列统一 timestamptz；网关写入 UTC，展示层按 Asia/Shanghai 转换。
--   3) 主键 uuid + gen_random_uuid()（PG 13 起为内核函数，无需 pgcrypto）。
--   4) 一帧设备报文 = 一行（体征分列）；幂等唯一键 (device_id, seq, measured_at)。
--   6) measured_at 永远保留设备原值（幂等键稳定，补传/重放才能命中唯一键）；
--      §3.6.3 的"偏差校正"结果写入 measured_at_corrected，展示/趋势按需取用。
--   5) 本表 append-only：采集明细不 UPDATE/DELETE；纠错走"新增修订表/事件表"，
--      由后续版本按 expand-contract 落地（见文件末说明）。
--
-- 为什么唯一键包含 measured_at：
--   设备侧 seq 单调递增，但设备掉电重启后可能从 1 重新计数；把设备测量时间纳入
--   唯一键，可在"序号回绕"时仍区分不同帧。补传（重放同一帧）三列完全相同，
--   由 INSERT ... ON CONFLICT (device_id, seq, measured_at) DO NOTHING 幂等丢弃，
--   表行数不增，网关侧 duplicates 指标 +1。
--
-- 为什么选"体征分列"而不是 metric_code/value 行式：
--   唯一键 (device_id, seq, measured_at) 是"一帧一唯一"，行式会让同一帧的多条体征
--   互相冲突。分列后一帧只有一行，读写放大最小，M2 趋势图按行取即可。新增体征指标
--   优先在后续版本加可空列（expand），确需行式时新增 anesthesia_vital_metric 表
--   双写过渡，不改本表语义。
--
-- expand-contract 说明：
--   expand   本版本只新增 schema 与表，不触碰任何既有表，旧代码（无采集）不受影响。
--   migrate  新指标用"加可空列 + 走默认值"扩展；分区（见下）用新表 + 双写切换。
--   contract 删除列/改类型/改语义必须新版本 + 双写 + ADR + 回滚演练记录；
--            本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
--
-- 容量与分区（正式上线前必须处理，POC 阶段不落分区）：
--   30 间 × 1 Hz × 86 400 s ≈ 259 万行/日、7 800 万行/月；
--   首期按 measured_at 月分区（PARTITION BY RANGE），历史分区归档到冷存储。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m2_anes;

COMMENT ON SCHEMA m2_anes IS
    'M2 麻醉记录 schema：记录单、事件、用药、设备体征；本次（V4）只建采集表，其余由 M2 后续版本扩展';

-- -----------------------------------------------------------------------------
-- 1. anesthesia_vital 设备采集体征明细（一帧一行）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.anesthesia_vital (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    gateway_id    varchar(64)   NOT NULL,
    device_id     varchar(64)   NOT NULL,
    seq           bigint        NOT NULL,
    measured_at   timestamptz   NOT NULL,
    measured_at_corrected timestamptz NOT NULL,
    received_at   timestamptz   NOT NULL,
    hr            smallint,
    spo2          smallint,
    nibp_sys      smallint,
    nibp_dia      smallint,
    etco2         numeric(5, 1),
    temp          numeric(4, 1),
    quality       smallint      NOT NULL DEFAULT 0,
    quality_flags varchar(200),
    source        varchar(16)   NOT NULL DEFAULT 'AUTO',
    synthetic     boolean       NOT NULL DEFAULT true,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_anesthesia_vital PRIMARY KEY (id),
    CONSTRAINT uk_anesthesia_vital_device_seq_measured UNIQUE (device_id, seq, measured_at),
    CONSTRAINT ck_anesthesia_vital_seq CHECK (seq >= 0),
    CONSTRAINT ck_anesthesia_vital_quality CHECK (quality IN (0, 1, 2, 3)),
    CONSTRAINT ck_anesthesia_vital_source CHECK (source IN ('AUTO', 'MANUAL', 'REPLAY')),
    -- 以下为"垃圾值护栏"，不是临床合理范围：超范围由网关打 quality=1 可疑标记后入库，
    -- 不在此处拒绝，避免"用检查约束丢弃体征"（§3.6.4 用标记而非丢弃）。
    CONSTRAINT ck_anesthesia_vital_hr CHECK (hr IS NULL OR hr BETWEEN 0 AND 1000),
    CONSTRAINT ck_anesthesia_vital_spo2 CHECK (spo2 IS NULL OR spo2 BETWEEN 0 AND 1000),
    CONSTRAINT ck_anesthesia_vital_nibp_sys CHECK (nibp_sys IS NULL OR nibp_sys BETWEEN 0 AND 1000),
    CONSTRAINT ck_anesthesia_vital_nibp_dia CHECK (nibp_dia IS NULL OR nibp_dia BETWEEN 0 AND 1000),
    CONSTRAINT ck_anesthesia_vital_etco2 CHECK (etco2 IS NULL OR etco2 BETWEEN 0 AND 1000),
    CONSTRAINT ck_anesthesia_vital_temp CHECK (temp IS NULL OR temp BETWEEN -50 AND 150)
);

COMMENT ON TABLE m2_anes.anesthesia_vital IS
    '设备采集体征明细（append-only）：一帧设备报文一行，单位见各列注释；'
    '唯一键 (device_id, seq, measured_at) 实现补传幂等；正式上线前按月分区';
COMMENT ON COLUMN m2_anes.anesthesia_vital.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.gateway_id IS
    '采集网关实例标识（部署单元），用于定位"哪台网关收的"；一院可多台网关';
COMMENT ON COLUMN m2_anes.anesthesia_vital.device_id IS
    '设备标识（厂商序列号或院内资产号，协议 v1 限制 [A-Za-z0-9._-]{1,64}）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.seq IS
    '设备侧帧序号，单调递增；设备重启可能回绕，故与 measured_at 共同构成幂等键';
COMMENT ON COLUMN m2_anes.anesthesia_vital.measured_at IS
    '设备测量时间（永远保留原值，作为幂等键一部分；校正结果见 measured_at_corrected）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.measured_at_corrected IS
    '时钟校正后的测量时间：未来时钟或 ≤120s 的过去偏差取接收时间，>120s 的补传/回放与 measured_at 相同；'
    '校正量记录在 quality_flags（ts:*）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.received_at IS
    '网关收到报文的服务端时间（timestamptz）；与 measured_at 的差值用于时钟偏差监控';
COMMENT ON COLUMN m2_anes.anesthesia_vital.hr IS '心率，单位 bpm（次/分）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.spo2 IS '脉搏血氧饱和度，单位 %（0–100）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.nibp_sys IS '无创血压收缩压，单位 mmHg';
COMMENT ON COLUMN m2_anes.anesthesia_vital.nibp_dia IS '无创血压舒张压，单位 mmHg';
COMMENT ON COLUMN m2_anes.anesthesia_vital.etco2 IS '呼气末二氧化碳，单位 mmHg，保留 1 位小数';
COMMENT ON COLUMN m2_anes.anesthesia_vital.temp IS '体温，单位 ℃（网关统一换算），保留 1 位小数';
COMMENT ON COLUMN m2_anes.anesthesia_vital.quality IS
    '数据质量：0=正常，1=可疑（超范围/单位不符/时钟偏差），2=缺失，3=超时（保留）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.quality_flags IS
    '质量标记明细（逗号分隔，如 hr:RANGE,ts:BACKFILL:-600000），供质控钻取与排障';
COMMENT ON COLUMN m2_anes.anesthesia_vital.source IS
    '数据来源：AUTO=设备自动采集，MANUAL=手工补录，REPLAY=补传重放（幂等仍由唯一键保证）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.synthetic IS
    '合成数据标记：POC/开发/测试恒为 true；接入真实设备后由部署配置写 false，'
    '禁止真实患者数据进入 dev/test（AGENTS 患者数据红线）';
COMMENT ON COLUMN m2_anes.anesthesia_vital.created_at IS '入库时间（服务端，timestamptz）';

-- 唯一约束已覆盖 (device_id, ...) 前缀；以下为查询与质控路径补索引。
-- 趋势图/记录单：某设备一段时间窗，按时间倒序取。
CREATE INDEX idx_anesthesia_vital_device_measured
    ON m2_anes.anesthesia_vital (device_id, measured_at DESC);
-- 跨设备时间窗（大屏/质控按时间扫）。
CREATE INDEX idx_anesthesia_vital_measured
    ON m2_anes.anesthesia_vital (measured_at);
-- 可疑数据钻取：只索引非正常质量行，避免全表膨胀。
CREATE INDEX idx_anesthesia_vital_suspect
    ON m2_anes.anesthesia_vital (device_id, measured_at DESC)
    WHERE quality <> 0;

-- -----------------------------------------------------------------------------
-- 2. 迁移自检（fail fast）：唯一键与关键列必须存在，否则迁移失败中止
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_anesthesia_vital_device_seq_measured'
          AND conrelid = 'm2_anes.anesthesia_vital'::regclass
    ) THEN
        RAISE EXCEPTION 'V4 迁移自检失败：缺少唯一键 uk_anesthesia_vital_device_seq_measured';
    END IF;
END
$$;
