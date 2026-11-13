-- =============================================================================
-- ANESOP 数据库迁移 V14：M2 设备登记与采集接入（M3-M8-01）
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 设计依据：
--   方案书 §3.6（设备采集：五段流水线、时间戳、离线与数据质量）、§3.6.5（离线判定与看板）
--   AGENTS.md 铁律 4（采集解耦：设备只采集不控制、断网缓存、幂等补传）
--   契约：packages/contracts/openapi/device.yaml 0.1.0（新增 device 域，additive）
--   任务卡：docs/ai/tasks/M3-backlog.md M3-M8-01
--
-- 内容：
--   1) m2_anes.device_registry   设备登记与术间绑定（唯一 gateway_id + device_id）；
--   2) m2_anes.device_ingest_log 网关批量上报幂等日志（唯一 gateway_id + batch_key）；
--   3) 权限点 m2:device:read / m2:device:write（授 SYS_ADMIN、SYS_OR_MANAGER）。
--
-- 幂等设计（两层，互不替代）：
--   a) 批次层：同一网关的同一 batch_key 只允许一条 device_ingest_log，网关断网补传/重试
--      命中唯一键即整批丢弃（返回 duplicateBatch=true），accepted_rows 不重复累加；
--   b) 行层：体征明细仍由 V4 的 uk_anesthesia_vital_device_seq_measured
--      (device_id, seq, measured_at) 兜底，同一帧无论走哪条批次都只入库一次。
--   两层都命中唯一键时 INSERT ... ON CONFLICT DO NOTHING，不抛异常、不中断整批。
--
-- 为什么 batch_key 的唯一键不含 device_id：
--   batch_key 由网关生成且全局唯一（含网关实例与设备信息）；若纳入 device_id，同一次上报
--   被拆成多设备写同一 key 时仍会重复入库，失去"整批幂等"语义。网关侧生成规则见
--   docs/gateway/README.md §11（gatewayId-deviceId-首帧seq-末帧seq-首帧毫秒）。
--
-- 设备登记与术间绑定：
--   room_id 是**逻辑外键**（→ m1_sched.operating_room），不建物理外键：设备登记属于 M2
--   采集子域，术间主数据属于 M1，跨模块数据一致性由 M1 接口在应用层校验（AGENTS 模块边界），
--   同时避免迁移顺序耦合。术间被删除/停用不影响历史体征（体征表本就不含 room_id）。
--
-- 设备离线判定：
--   本表只持久化 last_seen_at 与显式状态（ONLINE/OFFLINE/MAINTENANCE）；
--   "last_seen_at + 超时阈值 → OFFLINE" 的计算在服务层完成（阈值可配置，默认 120s），
--   避免为每个设备写定时 UPDATE，也不让"读操作"产生写放大。MAINTENANCE 由运维显式登记，
--   不会被自动改回 ONLINE。
--
-- expand-contract 说明：
--   expand   本版本只新增两张表、索引与权限数据，不修改 V1–V13 既有表/列/约束；
--            旧代码（无采集接入）不受影响，V4 的 anesthesia_vital 语义不变。
--   migrate  采集接入灰度期可继续由网关直连 PG（V4 唯一键幂等）；切到平台 ingest 后
--            两张新表开始积累，二者双写期由"行层唯一键"保证不重复。
--   contract 删列/改类型/改语义必须新版本 + 双写 + ADR + 回滚演练；本脚本合入后禁止修改
--            （Flyway checksum），修正一律新增 V<N+1>。
--
-- 迁移纪律：本脚本已合入后禁止修改。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.9）：
--   1) 应用回滚：下线 /v1/devices* 端点与网关 HTTP 上报开关
--      （anesop.gateway.platform.mode 改回 jdbc），网关继续直连 PG 写 anesthesia_vital；
--   2) 库层前向修复（不删表、不删体征数据，先停写再清理）：
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000040',
--                                'b0000000-0000-4000-8000-000000000041');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:device:read', 'm2:device:write');
--      设备登记与上报日志为运维辅助数据，回滚时可整表归档后 TRUNCATE
--      （m2_anes.device_ingest_log、m2_anes.device_registry），不影响临床体征明细；
--   3) 生产执行仍须人签字；回滚前先备份两张新表（pg_dump -t）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. device_registry 设备登记与术间绑定
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.device_registry (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    gateway_id    varchar(64)   NOT NULL,
    device_id     varchar(64)   NOT NULL,
    room_id       uuid,
    device_type   varchar(32)   NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'ONLINE',
    last_seen_at  timestamptz,
    quality_stats jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       bigint        NOT NULL DEFAULT 1,
    CONSTRAINT pk_device_registry PRIMARY KEY (id),
    CONSTRAINT uk_device_registry_gateway_device UNIQUE (gateway_id, device_id),
    CONSTRAINT ck_device_registry_status
        CHECK (status IN ('ONLINE', 'OFFLINE', 'MAINTENANCE')),
    CONSTRAINT ck_device_registry_type CHECK (device_type IN (
        'MONITOR', 'ANESTHESIA_MACHINE', 'INFUSION_PUMP', 'VENTILATOR', 'OTHER')),
    CONSTRAINT ck_device_registry_version CHECK (version >= 1),
    -- 标识格式与网关协议 v1 一致（[A-Za-z0-9._-]{1,64}），防止把路径字符写进登记表
    CONSTRAINT ck_device_registry_gateway_id
        CHECK (gateway_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_device_registry_device_id
        CHECK (device_id ~ '^[A-Za-z0-9._-]{1,64}$')
);

COMMENT ON TABLE m2_anes.device_registry IS
    '设备登记与术间绑定（M3-M8-01）：一台设备一行，唯一 (gateway_id, device_id)；'
    '质量统计与最后在线时间由平台 ingest 原子累加；room_id 为逻辑外键（不建物理外键）';
COMMENT ON COLUMN m2_anes.device_registry.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.device_registry.gateway_id IS
    '采集网关实例标识（部署单元），与 anesthesia_vital.gateway_id 同源';
COMMENT ON COLUMN m2_anes.device_registry.device_id IS
    '设备标识（厂商序列号或院内资产号），与 anesthesia_vital.device_id 同源';
COMMENT ON COLUMN m2_anes.device_registry.room_id IS
    '绑定术间 UUID（逻辑外键 → m1_sched.operating_room，跨模块不建物理外键）；'
    '未绑定为 NULL，可在同院换台时重新绑定';
COMMENT ON COLUMN m2_anes.device_registry.device_type IS
    '设备类型：MONITOR=监护仪，ANESTHESIA_MACHINE=麻醉机，INFUSION_PUMP=输注泵，'
    'VENTILATOR=呼吸机，OTHER=其他（真实型号清单待 D-06/EXT-04）';
COMMENT ON COLUMN m2_anes.device_registry.status IS
    '显式状态：ONLINE=在线，OFFLINE=运维置离线，MAINTENANCE=维护中（维护中不被自动改写）；'
    '"last_seen_at 超时"推导的离线为查询期视图，不写回本列';
COMMENT ON COLUMN m2_anes.device_registry.last_seen_at IS
    '最近一次成功接收该设备体征的服务端时间（timestamptz）；NULL 表示从未上报';
COMMENT ON COLUMN m2_anes.device_registry.quality_stats IS
    '质量统计 jsonb（不含 PHI）：{batches,acceptedRows,duplicateRows,rejectedRows,'
    'suspectRows,missingRows,lastBatchKey,lastBatchAtMs}；比例在查询期计算';
COMMENT ON COLUMN m2_anes.device_registry.created_at IS '登记时间（服务端，timestamptz）';
COMMENT ON COLUMN m2_anes.device_registry.created_by IS '登记人用户 UUID；网关自动上报不改本列';
COMMENT ON COLUMN m2_anes.device_registry.updated_at IS '最后更新时间（服务端，timestamptz）';
COMMENT ON COLUMN m2_anes.device_registry.updated_by IS '最后更新人用户 UUID；网关自动上报为 NULL';
COMMENT ON COLUMN m2_anes.device_registry.version IS
    '乐观锁版本号（从 1 起）：登记/换台绑定按 version 条件更新，冲突 409';

-- 绑定术间后的按术间查看（大屏/运维按术间定位设备）；未绑定设备不在该索引内。
CREATE INDEX idx_device_registry_room_id
    ON m2_anes.device_registry (room_id)
    WHERE room_id IS NOT NULL;
-- 在线看板：按网关 + 最后在线时间过滤（离线判定用）。
CREATE INDEX idx_device_registry_gateway_seen
    ON m2_anes.device_registry (gateway_id, last_seen_at DESC);

-- -----------------------------------------------------------------------------
-- 2. device_ingest_log 网关批量上报幂等日志
--    同一 (gateway_id, batch_key) 只允许一条：网关补传/重试整批命中唯一键即判重。
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.device_ingest_log (
    id             uuid          NOT NULL DEFAULT gen_random_uuid(),
    gateway_id     varchar(64)   NOT NULL,
    device_id      varchar(64)   NOT NULL,
    batch_key      varchar(128)  NOT NULL,
    received_at    timestamptz   NOT NULL,
    accepted_rows  integer       NOT NULL DEFAULT 0,
    duplicate_rows integer       NOT NULL DEFAULT 0,
    rejected_rows  integer       NOT NULL DEFAULT 0,
    CONSTRAINT pk_device_ingest_log PRIMARY KEY (id),
    CONSTRAINT uk_device_ingest_log_gateway_batch UNIQUE (gateway_id, batch_key),
    CONSTRAINT ck_device_ingest_log_batch_key
        CHECK (batch_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ck_device_ingest_log_counts
        CHECK (accepted_rows >= 0 AND duplicate_rows >= 0 AND rejected_rows >= 0)
);

COMMENT ON TABLE m2_anes.device_ingest_log IS
    '网关批量上报幂等日志（M3-M8-01）：一批一行，唯一 (gateway_id, batch_key)；'
    '只做幂等与质量统计，不存报文原文与体征值（体征在 anesthesia_vital）';
COMMENT ON COLUMN m2_anes.device_ingest_log.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.device_ingest_log.gateway_id IS
    '上报网关实例标识；与请求路径/令牌绑定，禁止跨网关复用 batch_key';
COMMENT ON COLUMN m2_anes.device_ingest_log.device_id IS
    '本批所属设备标识；一个 batch_key 只属于一台设备';
COMMENT ON COLUMN m2_anes.device_ingest_log.batch_key IS
    '网关生成的批次幂等键（1–128，[A-Za-z0-9._:-]）；补传/重试必须复用同一键';
COMMENT ON COLUMN m2_anes.device_ingest_log.received_at IS '平台接收该批的服务端时间（timestamptz）';
COMMENT ON COLUMN m2_anes.device_ingest_log.accepted_rows IS '本批新入库帧数（唯一键未冲突）';
COMMENT ON COLUMN m2_anes.device_ingest_log.duplicate_rows IS
    '本批重复帧数（行层唯一键冲突，含补传重放）';
COMMENT ON COLUMN m2_anes.device_ingest_log.rejected_rows IS
    '本批被平台校验拒绝的帧数（格式/范围/质量码非法），明细只返回原因码，不存原文';

-- 运维健康摘要：按网关/设备与时间窗统计批次与拒绝数。
CREATE INDEX idx_device_ingest_log_gateway_received
    ON m2_anes.device_ingest_log (gateway_id, received_at DESC);
CREATE INDEX idx_device_ingest_log_device_received
    ON m2_anes.device_ingest_log (device_id, received_at DESC);

-- -----------------------------------------------------------------------------
-- 3. 权限点 m2:device:read / m2:device:write
--    授 SYS_ADMIN（运维排障/登记）与 SYS_OR_MANAGER（手术室设备与术间绑定管理）。
--    不授临床角色：设备登记与采集接入是运维动作；体征查询由后续记录单权限复用。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000040', 'm2:device:read', 'M2 设备与体征只读', 'API',
     '/api/v1/devices', 'GET', NULL, 140),
    ('b0000000-0000-4000-8000-000000000041', 'm2:device:write', 'M2 设备登记与术间绑定', 'API',
     '/api/v1/devices', 'POST', NULL, 141);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：登记/换台绑定 + 采集质量排障读取
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000040'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000041'),
    -- SYS_OR_MANAGER：手术室设备台账与术间绑定（只读数据范围仍按 DEPT/ROOM 约定）
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000040'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000041');

-- -----------------------------------------------------------------------------
-- 4. 迁移自检（fail fast）：两张表、两层唯一键与两个权限点必须存在
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_device_registry_gateway_device'
          AND conrelid = 'm2_anes.device_registry'::regclass
    ) THEN
        RAISE EXCEPTION 'V14 迁移自检失败：缺少唯一键 uk_device_registry_gateway_device';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_device_ingest_log_gateway_batch'
          AND conrelid = 'm2_anes.device_ingest_log'::regclass
    ) THEN
        RAISE EXCEPTION 'V14 迁移自检失败：缺少唯一键 uk_device_ingest_log_gateway_batch';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_permission
        WHERE code IN ('m2:device:read', 'm2:device:write')) <> 2 THEN
        RAISE EXCEPTION 'V14 迁移自检失败：权限点 m2:device:read/write 未全部写入';
    END IF;
    IF (SELECT count(*) FROM m0_platform.sys_role_permission
        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000040',
                                'b0000000-0000-4000-8000-000000000041')) <> 4 THEN
        RAISE EXCEPTION 'V14 迁移自检失败：设备权限未按 2 角色 × 2 权限点授权';
    END IF;
END
$$;
