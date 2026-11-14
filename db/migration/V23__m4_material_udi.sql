-- =============================================================================
-- ANESOP 数据库迁移 V23：M4 耗材主数据、出入库、UDI 追溯与 SPD 适配器（M4-M14-01 / E-M14）
-- 目标库：PostgreSQL 16；schema：m4_material
--
-- 设计依据：
--   方案书 §2.5（药品与耗材管理）、§3.4（领域模型）、§3.9.2（RBAC 与数据权限）、
--   §3.9.3（脱敏）、§3.9 集成 EXT-03（SPD 供应链适配器模拟）
--   契约：packages/contracts/openapi/material.yaml 0.1.0
--   任务卡：docs/ai/tasks/M4-backlog.md M4-M14-01
--   ADR：docs/adr/0020-耗材UDI追溯与SPD口径.md
--
-- 内容（expand 段：只新增 schema/表/索引/约束/触发器/权限点，不改写 V1–V22 既有列与数据）：
--   1) m4_material.material        耗材主数据：编码/名称唯一、UDI-DI 非空时唯一、
--                                  高值(high_value)/效期管理(expiry_managed)标志、乐观锁 version。
--   2) m4_material.material_stock  批次库存：材料 + 批次 + 科室唯一，数量 >= 0（CHECK 兜底）。
--   3) m4_material.stock_movement  出入库流水：append-only（IN/OUT/RETURN/ADJUST），
--                                  数量规则 CHECK、ADJUST 必填原因、余额 >= 0。
--   4) m4_material.udi_instance    UDI 实例：UDI-DI + UDI-PI 唯一（一物一码），
--                                  状态 IN_STOCK/USED/RETURNED，record_id/schedule_id 逻辑关联
--                                  （跨模块不建外键），状态迁移与身份列由触发器守卫。
--   5) m4_material.spd_sync_log    SPD 适配器（EXT-03）同步/降级留痕：只存状态摘要。
--   6) 权限点 m4:material:read、m4:material:write、m4:udi:manage（三段式编码），
--      最小授予 SYS_ADMIN（三项）、SYS_OR_MANAGER（读/写/UDI 验收）、
--      SYS_NURSE_ANESTHETIST（读 + UDI 使用）；不授予术者/药房/人员管理员。
--
-- 临床/管理口径约定与待确认：
--   - **UDI 编码规范【待确认】**：本表内部规范编码 udi_code = udi_di || '^' || udi_pi；
--     GS1/HIBCC 的 DI/PI 分隔符、校验位、PI 内嵌批号/效期/序列号的解析规则未确认，
--     本期只做字符串唯一与长度校验，不解析 DI 结构、不校验证书与 GUDID。
--   - **批次策略【待确认】**：批次以“材料 + 批次 + 科室”唯一；同一批次在不同科室分别
--     记账（支持中心库房与手术室二级库）；同一材料 + 批次 + 科室的效期必须一致（服务层 409，
--     数据库不做跨行比较）。批次是否允许跨科室调拨、是否按供应商批次合并记账待院方确认。
--   - **高值口径【待确认】**：high_value 为院内维护标志，本期用于“必须逐件 UDI 登记/使用”
--     的强制校验；金额阈值（如 ≥500 元）与高值目录范围由院方/医保口径确认后另行迁移，
--     不在本表内置金额字段（避免把财务口径写死成临床规则）。
--   - **数量不足/效期**：OUT/RETURN/负向 ADJUST 超库存 → 服务层 409（details 给可用量），
--     库存 CHECK >= 0 为并发兜底；效期管理材料过期/缺效期 → 422，不得入库或使用。
--     扣减前按 材料 + 批次 + 科室 定位库存行校验请求效期（不一致 409，数据库不做跨行比较）；
--     过期批次 OUT → 422；RETURN 携带 UDI 时以 udi_instance 的批次/效期为扣减依据
--     （请求批次/效期与实例不一致 422），不得按请求批次扣错行。
--   - **RETURN 语义**：退回供应商/中心库（库存减少 + UDI 终态 RETURNED，单向）；院内退库
--     复库/再用登记口径【待确认】（ADR-0020 Q-6 / 模块 README Q-7）。
--   - **主数据启停**：复用 material.status（ACTIVE/INACTIVE，PATCH 状态端点），停用后服务层
--     拒绝新出入库/UDI 登记；存在 UDI 实例或库存 > 0 时修改/清空 udi_di → 服务层 409。
--   - **SPD EXT-03 为 SIMULATED**：不直连 SPD/供应链生产库，不写入外部系统；
--     同步失败只记录 spd_sync_log 并降级为“本地库存继续可用”，不阻塞临床与本地出入库。
--   - **脱敏**：流水只存操作人标识 + 姓名摘要、来源/去向文本与原因，不含患者姓名、
--     院内患者号、身份证号、手机号；UDI 追溯只回传 record_id/schedule_id，不回传患者信息。
--
-- 运行时权限要求：append-only 与状态机守卫依赖触发器/函数，应用运行账号不得拥有 DDL 权限
-- （CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写流水；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.18）：
--   1) 应用回滚：下线 /v1/materials* 端点与前端耗材目录/出入库/UDI 追溯菜单；
--      库存与追溯数据保留（不影响 M2/M3 记录单，UDI 绑定仅为逻辑关联）。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 库存流水归档，顺序不可颠倒）：
--      DROP TRIGGER IF EXISTS trg_stock_movement_no_update ON m4_material.stock_movement;
--      DROP TRIGGER IF EXISTS trg_stock_movement_no_delete ON m4_material.stock_movement;
--      DROP TRIGGER IF EXISTS trg_stock_movement_no_truncate ON m4_material.stock_movement;
--      DROP TRIGGER IF EXISTS trg_udi_instance_guard ON m4_material.udi_instance;
--      DROP TRIGGER IF EXISTS trg_material_guard ON m4_material.material;
--      DROP FUNCTION IF EXISTS m4_material.fn_reject_mutation();
--      DROP FUNCTION IF EXISTS m4_material.fn_reject_truncate();
--      DROP FUNCTION IF EXISTS m4_material.fn_udi_guard();
--      DROP FUNCTION IF EXISTS m4_material.fn_material_guard();
--      DROP TABLE m4_material.spd_sync_log;
--      DROP TABLE m4_material.udi_instance;
--      DROP TABLE m4_material.stock_movement;
--      DROP TABLE m4_material.material_stock;
--      DROP TABLE m4_material.material;
--      DROP SCHEMA IF EXISTS m4_material;
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000074',
--                                'b0000000-0000-4000-8000-000000000075',
--                                'b0000000-0000-4000-8000-000000000076');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m4:material:read', 'm4:material:write', 'm4:udi:manage');
--      回滚前必须导出 stock_movement / udi_instance 并校验行数；高值追溯属质量追溯留痕，
--      须先归档到离线追溯库，生产执行须人签字（不可逆脚本）。
--   3) 流水与已使用 UDI 不得回灌（append-only 与一物一码语义）：如需恢复，按原 UDI 编码/
--      原时间重新登记，并在业务说明中注明补录原因（须耗材管理员与临床确认）。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m4_material;

COMMENT ON SCHEMA m4_material IS
    'M4 耗材主数据、批次库存、出入库流水、UDI 一物一码追溯与 SPD 适配器留痕（material/material_stock/stock_movement/udi_instance/spd_sync_log）；其他模块只能经 m4-api 访问';

-- -----------------------------------------------------------------------------
-- 1. material 耗材主数据
--    编码/名称院内唯一；UDI-DI 非空时唯一（扫码反查主数据的语义前提）。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_material.material (
    id             uuid          NOT NULL DEFAULT gen_random_uuid(),
    code           varchar(64)   NOT NULL,
    name           varchar(200)  NOT NULL,
    specification  varchar(100)  NOT NULL,
    unit           varchar(16)   NOT NULL,
    category       varchar(32)   NOT NULL,
    udi_di         varchar(64),
    high_value     boolean       NOT NULL DEFAULT false,
    expiry_managed boolean       NOT NULL DEFAULT true,
    status         varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    remark         varchar(500),
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     uuid,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     uuid,
    version        integer       NOT NULL DEFAULT 1,
    CONSTRAINT pk_material PRIMARY KEY (id),
    CONSTRAINT ck_material_code CHECK (code ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_material_udi_di CHECK (udi_di ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_material_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_material_version CHECK (version >= 1),
    CONSTRAINT ck_material_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_material_unit_not_blank CHECK (btrim(unit) <> '')
);

-- 编码大小写不敏感唯一（服务层先查重返回 409，唯一索引是并发兜底）。
CREATE UNIQUE INDEX uk_material_code ON m4_material.material (lower(btrim(code)));
-- 名称唯一（trim + 忽略大小写）。
CREATE UNIQUE INDEX uk_material_name ON m4_material.material (lower(btrim(name)));
-- UDI-DI 非空时全局唯一：同一 DI 只能对应一个院内耗材，否则扫码无法定位主数据。
CREATE UNIQUE INDEX uk_material_udi_di ON m4_material.material (lower(btrim(udi_di)))
    WHERE udi_di IS NOT NULL;
CREATE INDEX idx_material_category ON m4_material.material (category);
CREATE INDEX idx_material_high_value ON m4_material.material (high_value);
CREATE INDEX idx_material_status ON m4_material.material (status);

COMMENT ON TABLE m4_material.material IS
    '耗材主数据（编码/名称唯一、UDI-DI 非空唯一）；high_value 决定是否必须逐件 UDI 登记/使用';
COMMENT ON COLUMN m4_material.material.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m4_material.material.code IS '院内耗材编码（唯一、大小写不敏感，创建后不可改）';
COMMENT ON COLUMN m4_material.material.name IS '耗材名称（trim+大小写不敏感唯一）';
COMMENT ON COLUMN m4_material.material.specification IS '规格（如 7F 三腔）';
COMMENT ON COLUMN m4_material.material.unit IS '单位（套/个/副/包等）';
COMMENT ON COLUMN m4_material.material.category IS '耗材分类编码（院内字典；具体取值【待确认】）';
COMMENT ON COLUMN m4_material.material.udi_di IS
    'UDI-DI 器械标识（可空；非空时院内唯一，用于扫码反查耗材）';
COMMENT ON COLUMN m4_material.material.high_value IS
    '是否高值耗材（院内维护标志，不内置金额阈值【待确认】）：true 时禁止裸数量出入库，必须逐件 UDI';
COMMENT ON COLUMN m4_material.material.expiry_managed IS
    '是否效期管理：true 时出入库必须带批次与效期，过期/缺效期拒绝';
COMMENT ON COLUMN m4_material.material.status IS '状态：ACTIVE=启用（可入库），INACTIVE=停用（只读历史）';
COMMENT ON COLUMN m4_material.material.remark IS '备注（可选，不含患者信息）';
COMMENT ON COLUMN m4_material.material.version IS '乐观锁版本号（从 1 起；更新必须回传，过期 409）';

-- 耗材守卫：编码不可改、版本只增、身份与创建留痕不可改。
CREATE OR REPLACE FUNCTION m4_material.fn_material_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.code IS DISTINCT FROM OLD.code
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_material.material identity columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '耗材编码/创建留痕不可修改；停用请改 status';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'm4_material.material version must increase: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = '更新必须递增 version（乐观锁），不得静默覆盖';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_material.fn_material_guard() IS
    '耗材守卫：id/code/created_* 不可变、version 只增（55000）';

CREATE TRIGGER trg_material_guard
    BEFORE UPDATE ON m4_material.material
    FOR EACH ROW EXECUTE FUNCTION m4_material.fn_material_guard();

-- -----------------------------------------------------------------------------
-- 2. material_stock 批次库存（材料 + 批次 + 科室唯一，数量 >= 0）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_material.material_stock (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    material_id   uuid          NOT NULL,
    material_code varchar(64)   NOT NULL,
    material_name varchar(200)  NOT NULL,
    batch_no      varchar(64)   NOT NULL,
    department_id uuid          NOT NULL,
    quantity      numeric(12,2) NOT NULL DEFAULT 0,
    unit          varchar(16)   NOT NULL,
    expiry_date   date,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       integer       NOT NULL DEFAULT 1,
    CONSTRAINT pk_material_stock PRIMARY KEY (id),
    CONSTRAINT fk_material_stock_material FOREIGN KEY (material_id)
        REFERENCES m4_material.material (id) ON DELETE RESTRICT,
    CONSTRAINT ck_material_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_material_stock_version CHECK (version >= 1),
    CONSTRAINT ck_material_stock_batch_not_blank CHECK (btrim(batch_no) <> ''),
    CONSTRAINT uk_material_stock_batch UNIQUE (material_id, batch_no, department_id)
);

CREATE INDEX idx_material_stock_department ON m4_material.material_stock (department_id);
CREATE INDEX idx_material_stock_material ON m4_material.material_stock (material_id);
CREATE INDEX idx_material_stock_expiry ON m4_material.material_stock (expiry_date);

COMMENT ON TABLE m4_material.material_stock IS
    '批次库存：材料 + 批次 + 科室唯一；数量 >= 0（CHECK 兜底）；余额以 append-only 流水重算为准';
COMMENT ON COLUMN m4_material.material_stock.material_code IS '耗材编码快照（列表展示与追溯，不随主数据改名回溯）';
COMMENT ON COLUMN m4_material.material_stock.material_name IS '耗材名称快照';
COMMENT ON COLUMN m4_material.material_stock.batch_no IS '批次号（同一材料 + 批次 + 科室的效期必须一致）';
COMMENT ON COLUMN m4_material.material_stock.department_id IS '科室标识（二级库归属与数据范围过滤依据）';
COMMENT ON COLUMN m4_material.material_stock.quantity IS '当前数量（>= 0；并发扣减由服务层守卫 + CHECK 兜底）';
COMMENT ON COLUMN m4_material.material_stock.expiry_date IS '效期（效期管理耗材必填；同一批次不得前后不一致）';
COMMENT ON COLUMN m4_material.material_stock.version IS '乐观锁版本号（每次变动递增）';

-- -----------------------------------------------------------------------------
-- 3. stock_movement 出入库流水（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_material.stock_movement (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    movement_type varchar(8)    NOT NULL,
    material_id   uuid          NOT NULL,
    material_code varchar(64)   NOT NULL,
    material_name varchar(200)  NOT NULL,
    batch_no      varchar(64)   NOT NULL,
    expiry_date   date,
    udi_code      varchar(160),
    quantity      numeric(12,2) NOT NULL,
    unit          varchar(16)   NOT NULL,
    department_id uuid          NOT NULL,
    counterparty  varchar(100)  NOT NULL,
    reason        varchar(200),
    balance_after numeric(12,2) NOT NULL,
    record_id     uuid,
    schedule_id   uuid,
    operator_id   uuid          NOT NULL,
    operator_name varchar(64)   NOT NULL,
    occurred_at   timestamptz   NOT NULL DEFAULT now(),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid,
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT fk_stock_movement_material FOREIGN KEY (material_id)
        REFERENCES m4_material.material (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_movement_type CHECK (movement_type IN ('IN', 'OUT', 'RETURN', 'ADJUST')),
    -- IN/OUT/RETURN 必须为正数；ADJUST 为带符号调整量且不得为 0。
    CONSTRAINT ck_stock_movement_quantity CHECK (
        (movement_type IN ('IN', 'OUT', 'RETURN') AND quantity > 0)
        OR (movement_type = 'ADJUST' AND quantity <> 0)
    ),
    CONSTRAINT ck_stock_movement_balance CHECK (balance_after >= 0),
    CONSTRAINT ck_stock_movement_adjust_reason CHECK (
        movement_type <> 'ADJUST' OR (reason IS NOT NULL AND btrim(reason) <> '')
    ),
    CONSTRAINT ck_stock_movement_counterparty_not_blank CHECK (btrim(counterparty) <> ''),
    CONSTRAINT ck_stock_movement_operator CHECK (
        operator_id IS NOT NULL AND btrim(operator_name) <> ''
    )
);

CREATE INDEX idx_stock_movement_material ON m4_material.stock_movement (material_id);
CREATE INDEX idx_stock_movement_department ON m4_material.stock_movement (department_id);
CREATE INDEX idx_stock_movement_occurred_at ON m4_material.stock_movement (occurred_at);
CREATE INDEX idx_stock_movement_batch ON m4_material.stock_movement (material_id, batch_no);
CREATE INDEX idx_stock_movement_udi ON m4_material.stock_movement (udi_code);
CREATE INDEX idx_stock_movement_record ON m4_material.stock_movement (record_id);

COMMENT ON TABLE m4_material.stock_movement IS
    '出入库流水（append-only）：IN/OUT/RETURN/ADJUST；数量不足由服务层 409，余额 >= 0 由 CHECK 兜底';
COMMENT ON COLUMN m4_material.stock_movement.movement_type IS '类型：IN=入库，OUT=出库，RETURN=退回，ADJUST=盘点调整（带符号）';
COMMENT ON COLUMN m4_material.stock_movement.material_code IS '耗材编码快照';
COMMENT ON COLUMN m4_material.stock_movement.material_name IS '耗材名称快照';
COMMENT ON COLUMN m4_material.stock_movement.batch_no IS '批次号';
COMMENT ON COLUMN m4_material.stock_movement.expiry_date IS '效期（效期管理耗材必填）';
COMMENT ON COLUMN m4_material.stock_movement.udi_code IS '关联 UDI 编码（高值/UDI 管理耗材的逐件流水）';
COMMENT ON COLUMN m4_material.stock_movement.quantity IS
    '数量：IN/OUT/RETURN 为正数；ADJUST 为带符号调整量（不得为 0）';
COMMENT ON COLUMN m4_material.stock_movement.department_id IS '科室标识（数据范围过滤依据）';
COMMENT ON COLUMN m4_material.stock_movement.counterparty IS '来源/去向（供应商/库房/科室等，不含患者信息）';
COMMENT ON COLUMN m4_material.stock_movement.reason IS '原因/备注（ADJUST 必填）';
COMMENT ON COLUMN m4_material.stock_movement.balance_after IS '本次流水后该批次在本科室的库存余额（>= 0）';
COMMENT ON COLUMN m4_material.stock_movement.record_id IS
    '使用绑定记录标识（逻辑关联 m2 记录，跨模块不建外键；仅 UDI 使用产生的 OUT 流水）';
COMMENT ON COLUMN m4_material.stock_movement.schedule_id IS '使用绑定台次标识（逻辑关联，跨模块不建外键）';
COMMENT ON COLUMN m4_material.stock_movement.operator_id IS '操作人用户标识（服务端按调用者固化）';
COMMENT ON COLUMN m4_material.stock_movement.operator_name IS '操作人姓名摘要（<=64）';
COMMENT ON COLUMN m4_material.stock_movement.occurred_at IS '发生时间（服务端时间）';

-- append-only 守卫（流水/SPD 日志）
CREATE OR REPLACE FUNCTION m4_material.fn_reject_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000',
              HINT = '出入库流水不得修改或删除；更正请用反向 ADJUST 并填写原因';
END;
$$;

CREATE OR REPLACE FUNCTION m4_material.fn_reject_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: TRUNCATE is not permitted', TG_TABLE_NAME
        USING ERRCODE = '55000',
              HINT = '出入库流水不得清空；归档请走离线追溯库';
END;
$$;

COMMENT ON FUNCTION m4_material.fn_reject_mutation() IS
    'append-only 行级守卫：UPDATE/DELETE 拒绝（55000）';
COMMENT ON FUNCTION m4_material.fn_reject_truncate() IS
    'append-only 语句级守卫：TRUNCATE 拒绝（55000）';

CREATE TRIGGER trg_stock_movement_no_update
    BEFORE UPDATE ON m4_material.stock_movement
    FOR EACH ROW EXECUTE FUNCTION m4_material.fn_reject_mutation();
CREATE TRIGGER trg_stock_movement_no_delete
    BEFORE DELETE ON m4_material.stock_movement
    FOR EACH ROW EXECUTE FUNCTION m4_material.fn_reject_mutation();
CREATE TRIGGER trg_stock_movement_no_truncate
    BEFORE TRUNCATE ON m4_material.stock_movement
    FOR EACH STATEMENT EXECUTE FUNCTION m4_material.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 4. udi_instance UDI 实例（一物一码，状态机 IN_STOCK → USED / RETURNED）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_material.udi_instance (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    udi_code      varchar(160)  NOT NULL,
    udi_di        varchar(64)   NOT NULL,
    udi_pi        varchar(96)   NOT NULL,
    serial_number varchar(96),
    material_id   uuid          NOT NULL,
    material_code varchar(64)   NOT NULL,
    material_name varchar(200)  NOT NULL,
    batch_no      varchar(64)   NOT NULL,
    expiry_date   date,
    department_id uuid          NOT NULL,
    status        varchar(16)   NOT NULL DEFAULT 'IN_STOCK',
    record_id     uuid,
    schedule_id   uuid,
    used_at       timestamptz,
    used_by       uuid,
    used_by_name  varchar(64),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       integer       NOT NULL DEFAULT 1,
    CONSTRAINT pk_udi_instance PRIMARY KEY (id),
    CONSTRAINT fk_udi_instance_material FOREIGN KEY (material_id)
        REFERENCES m4_material.material (id) ON DELETE RESTRICT,
    CONSTRAINT ck_udi_instance_status CHECK (status IN ('IN_STOCK', 'USED', 'RETURNED')),
    CONSTRAINT ck_udi_instance_components CHECK (
        udi_code = udi_di || '^' || udi_pi
    ),
    -- 状态与绑定字段必须一致：在库/退回不得有绑定记录；已使用必须有记录、台次与使用时间。
    CONSTRAINT ck_udi_instance_state CHECK (
        (status = 'IN_STOCK'
            AND record_id IS NULL AND schedule_id IS NULL
            AND used_at IS NULL AND used_by IS NULL)
        OR (status = 'USED'
            AND record_id IS NOT NULL AND schedule_id IS NOT NULL
            AND used_at IS NOT NULL AND used_by IS NOT NULL)
        OR (status = 'RETURNED'
            AND record_id IS NULL AND schedule_id IS NULL
            AND used_at IS NULL AND used_by IS NULL)
    ),
    CONSTRAINT ck_udi_instance_version CHECK (version >= 1),
    CONSTRAINT ck_udi_instance_batch_not_blank CHECK (btrim(batch_no) <> '')
);

-- 一物一码：UDI-DI + UDI-PI 归一化（trim + 忽略大小写）唯一；服务层先查重返回 409，
-- 唯一索引是并发兜底（GS1 字母大小写口径【待确认】，归一化取保守口径）。
CREATE UNIQUE INDEX uk_udi_instance_di_pi
    ON m4_material.udi_instance (lower(btrim(udi_di)), lower(btrim(udi_pi)));
CREATE UNIQUE INDEX uk_udi_instance_udi_code ON m4_material.udi_instance (udi_code);
CREATE INDEX idx_udi_instance_material ON m4_material.udi_instance (material_id);
CREATE INDEX idx_udi_instance_department ON m4_material.udi_instance (department_id);
CREATE INDEX idx_udi_instance_status ON m4_material.udi_instance (status);
CREATE INDEX idx_udi_instance_record ON m4_material.udi_instance (record_id);
CREATE INDEX idx_udi_instance_batch ON m4_material.udi_instance (material_id, batch_no);

COMMENT ON TABLE m4_material.udi_instance IS
    'UDI 实例（一物一码）：UDI-DI+UDI-PI 唯一；状态 IN_STOCK/USED/RETURNED；record_id/schedule_id 为逻辑关联';
COMMENT ON COLUMN m4_material.udi_instance.udi_code IS
    'UDI 内部规范编码 = udi_di || ''^'' || udi_pi（GS1/HIBCC 解析规则【待确认】）';
COMMENT ON COLUMN m4_material.udi_instance.udi_di IS 'UDI-DI 器械标识';
COMMENT ON COLUMN m4_material.udi_instance.udi_pi IS 'UDI-PI 生产标识（序列号/批号/效期组合；不解析内部结构）';
COMMENT ON COLUMN m4_material.udi_instance.serial_number IS '序列号（可选，冗余展示）';
COMMENT ON COLUMN m4_material.udi_instance.material_code IS '耗材编码快照';
COMMENT ON COLUMN m4_material.udi_instance.material_name IS '耗材名称快照';
COMMENT ON COLUMN m4_material.udi_instance.expiry_date IS '效期（效期管理耗材必填）';
COMMENT ON COLUMN m4_material.udi_instance.department_id IS '归属科室（数据范围过滤依据）';
COMMENT ON COLUMN m4_material.udi_instance.status IS '状态：IN_STOCK=在库，USED=已使用，RETURNED=已退回（USED/RETURNED 为终态）';
COMMENT ON COLUMN m4_material.udi_instance.record_id IS '使用绑定的麻醉记录标识（逻辑关联，跨模块不建外键）';
COMMENT ON COLUMN m4_material.udi_instance.schedule_id IS '使用绑定的台次标识（逻辑关联）';
COMMENT ON COLUMN m4_material.udi_instance.used_at IS '使用时间';
COMMENT ON COLUMN m4_material.udi_instance.used_by IS '使用人用户标识';
COMMENT ON COLUMN m4_material.udi_instance.used_by_name IS '使用人姓名摘要（<=64）';

-- UDI 守卫：身份列不可改；状态只能 IN_STOCK → USED/RETURNED；终态不可再变；版本只增。
CREATE OR REPLACE FUNCTION m4_material.fn_udi_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.udi_code IS DISTINCT FROM OLD.udi_code
       OR NEW.udi_di IS DISTINCT FROM OLD.udi_di
       OR NEW.udi_pi IS DISTINCT FROM OLD.udi_pi
       OR NEW.material_id IS DISTINCT FROM OLD.material_id
       OR NEW.batch_no IS DISTINCT FROM OLD.batch_no
       OR NEW.department_id IS DISTINCT FROM OLD.department_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_material.udi_instance identity columns are immutable'
            USING ERRCODE = '55000',
                  HINT = 'UDI 一物一码身份（编码/耗材/批次/科室）不可修改；错误登记请走退回后重新登记';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF OLD.status <> 'IN_STOCK'
           OR NEW.status NOT IN ('USED', 'RETURNED') THEN
            RAISE EXCEPTION 'm4_material.udi_instance illegal status transition: % -> %',
                OLD.status, NEW.status
                USING ERRCODE = '55000',
                      HINT = 'UDI 状态只能 IN_STOCK → USED/RETURNED，终态不可再变';
        END IF;
    ELSIF OLD.status <> 'IN_STOCK' THEN
        RAISE EXCEPTION 'm4_material.udi_instance is terminal in status %', OLD.status
            USING ERRCODE = '55000',
                  HINT = '已使用/已退回的 UDI 不得再修改绑定信息';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'm4_material.udi_instance version must increase: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = 'UDI 状态更新必须递增 version';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_material.fn_udi_guard() IS
    'UDI 守卫：身份列不可变、状态只能 IN_STOCK → USED/RETURNED、终态不可变、version 只增（55000）';

CREATE TRIGGER trg_udi_instance_guard
    BEFORE UPDATE ON m4_material.udi_instance
    FOR EACH ROW EXECUTE FUNCTION m4_material.fn_udi_guard();

-- -----------------------------------------------------------------------------
-- 5. spd_sync_log SPD 适配器（EXT-03）同步/降级留痕
-- -----------------------------------------------------------------------------
CREATE TABLE m4_material.spd_sync_log (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    adapter_code varchar(16) NOT NULL DEFAULT 'EXT-03',
    operation    varchar(32) NOT NULL,
    success      boolean     NOT NULL,
    degraded     boolean     NOT NULL DEFAULT false,
    detail       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_spd_sync_log PRIMARY KEY (id),
    CONSTRAINT ck_spd_sync_log_adapter CHECK (adapter_code = 'EXT-03')
);

CREATE INDEX idx_spd_sync_log_occurred_at ON m4_material.spd_sync_log (occurred_at);

COMMENT ON TABLE m4_material.spd_sync_log IS
    'SPD 适配器（EXT-03）模拟同步/降级留痕：只存状态摘要，不含外部凭据、供应商明细与患者信息';
COMMENT ON COLUMN m4_material.spd_sync_log.operation IS '操作类型（SYNC/STATUS 等）';
COMMENT ON COLUMN m4_material.spd_sync_log.degraded IS '是否降级（本地耗材库存继续可用）';
COMMENT ON COLUMN m4_material.spd_sync_log.detail IS '状态摘要 jsonb（错误码/条数等，不含凭据与患者信息）';

-- -----------------------------------------------------------------------------
-- 6. 权限点与最小授权（M4-M14-01）
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000074', 'm4:material:read', 'M4 耗材目录与库存只读', 'API',
     '/api/v1/materials', 'GET', NULL, 143),
    ('b0000000-0000-4000-8000-000000000075', 'm4:material:write', 'M4 耗材目录与出入库维护', 'API',
     '/api/v1/materials', 'POST', NULL, 144),
    ('b0000000-0000-4000-8000-000000000076', 'm4:udi:manage', 'M4 UDI 登记与使用', 'API',
     '/api/v1/materials/udi-instances', 'POST', NULL, 145);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：耗材目录/出入库/UDI 全量（运维排障与初始化）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000074'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000075'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000076'),
    -- SYS_OR_MANAGER（DEPT 数据范围）：耗材目录、普通出入库、高值到货 UDI 验收
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000074'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000075'),
    ('a0000000-0000-4000-8000-000000000005', 'b0000000-0000-4000-8000-000000000076'),
    -- SYS_NURSE_ANESTHETIST：术中扫码使用/追溯 + 只读目录；不授予目录维护与普通出入库
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000074'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000076');

-- -----------------------------------------------------------------------------
-- 7. 演示耗材不在生产迁移路径
--    生产 locations 只含 db/migration，迁移后 m4_material.* 为空，由耗材管理员维护真实目录；
--    自动化测试/E2E 使用 SYNTHETIC 数据在线创建，不写死真实耗材与 UDI。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 8. 迁移自检：表/函数/权限与最小授权必须齐全（fail-fast，避免半套 schema 上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_tables integer;
    missing_functions integer;
    missing_permissions integer;
    missing_grants integer;
BEGIN
    SELECT count(*) INTO missing_tables
      FROM (VALUES
            ('m4_material.material'), ('m4_material.material_stock'),
            ('m4_material.stock_movement'), ('m4_material.udi_instance'),
            ('m4_material.spd_sync_log')) AS t(name)
     WHERE to_regclass(t.name) IS NULL;
    IF missing_tables > 0 THEN
        RAISE EXCEPTION 'V23 自检失败：缺少 % 张表', missing_tables;
    END IF;

    SELECT count(*) INTO missing_functions
      FROM (VALUES
            ('m4_material.fn_material_guard()'), ('m4_material.fn_udi_guard()'),
            ('m4_material.fn_reject_mutation()'), ('m4_material.fn_reject_truncate()')) AS f(name)
     WHERE to_regprocedure(f.name) IS NULL;
    IF missing_functions > 0 THEN
        RAISE EXCEPTION 'V23 自检失败：缺少 % 个函数', missing_functions;
    END IF;

    SELECT count(*) INTO missing_permissions
      FROM (VALUES ('m4:material:read'), ('m4:material:write'), ('m4:udi:manage')) AS p(code)
     WHERE NOT EXISTS (
         SELECT 1 FROM m0_platform.sys_permission sp WHERE sp.code = p.code
     );
    IF missing_permissions > 0 THEN
        RAISE EXCEPTION 'V23 自检失败：缺少 % 个权限点', missing_permissions;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm4:material:read'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:material:write'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:udi:manage'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:material:read'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:material:write'),
            ('a0000000-0000-4000-8000-000000000005', 'm4:udi:manage'),
            ('a0000000-0000-4000-8000-000000000003', 'm4:material:read'),
            ('a0000000-0000-4000-8000-000000000003', 'm4:udi:manage')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V23 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;
END;
$$;
