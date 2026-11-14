-- =============================================================================
-- ANESOP 数据库迁移 V20：M4 药品目录与麻精五专（M4-M15-01 / E-M15）
-- 目标库：PostgreSQL 16；schema：m4_drug
--
-- 设计依据：
--   方案书 §2.5（药品与耗材管理、麻精五专：专人/专柜/专册/专账/专处方）、
--   §3.4.2（药品主数据与五专实体）、§3.4.3（审计字段/乐观锁）、
--   §3.9.2（RBAC 与数据权限）、§3.9.3（脱敏）、§3.9 集成 I-07/EXT-02（药房适配器模拟）
--   契约：packages/contracts/openapi/drug.yaml 0.1.0
--   任务卡：docs/ai/tasks/M4-backlog.md M4-M15-01（含 M3 T-4 收口：麻精判定改药品字典）
--   ADR：docs/adr/0018-药品字典与麻精五专口径.md
--
-- 内容（expand 段：只新增 schema/表/索引/约束/触发器/权限点与 SYNTHETIC 种子，
-- 不改写 V1–V19 既有列与数据）：
--   1) m4_drug.drug            药品主数据：编码唯一、名称唯一、麻精(narcotic)/高危(high_risk)
--                              标志、状态 ACTIVE/INACTIVE、乐观锁 version。
--   2) m4_drug.drug_alias      药品别名：同一药品内唯一，且 **全局唯一**（lower(alias)），
--                              保证麻醉记录按名称解析麻精属性时只有一个候选。
--   3) m4_drug.narcotic_register 麻精专册登记：药品/批号/数量/处方号/使用人/去向 +
--                              双人签字（first_signer <> second_signer），append-only。
--   4) m4_drug.cabinet_handover  专柜交接：班次/交班人/接班人/品种数量明细(jsonb) +
--                              双人签字（且交班人 <> 接班人），append-only。
--   5) m4_drug.drug_baseline    麻精基数：品种 + 柜唯一，数量 ≥ 0，乐观锁 version。
--   6) m4_drug.daily_close      日结/班结：期初/入库/使用/退回/期末，CHECK 平账，
--                              同一柜 + 业务日 + 班次唯一，append-only。
--   7) m4_drug.pharmacy_adapter_log 药房适配器（EXT-02）同步尝试留痕：成功/失败/降级，
--                              detail 只存状态摘要（不含外部凭据与患者信息）。
--   8) 权限点 m4:drug:read、m4:drug:write、m4:narcotic:manage（三段式编码）；
--      新增 SYNTHETIC 演示角色 SYS_PHARMACIST（药房药师，R6/R7 之间最小集），
--      三个权限点授予 SYS_ADMIN、SYS_PHARMACIST、SYS_ANESTHESIOLOGIST（最小集）；
--      麻醉护士只授予 m4:drug:read（用药页字典联想），不授予五专维护，避免越权维护麻精账；
--      术者不授予目录权限。
--   9) 演示药品 3 条（舒芬太尼/丙泊酚/吗啡）不在生产迁移路径：见
--      db/seed/demo/V101__demo_drug_synthetic.sql（仅 dev/test/uat 显式加载）；生产由药房
--      经维护页录入真实目录。麻精标志以本字典为唯一权威（M3 T-4 收口）。
--
-- 临床/财务口径约定与待确认：
--   - **班次口径【待确认】**：数据库只允许 DAY/NIGHT 两班制（演示口径），院方如为三班制
--     或按“日结 + 班结”双轨，需要新增迁移扩展 CHECK 与契约枚举，不改本脚本。
--   - **基数上限/下限、日结差异容忍度【待确认】**：本期只做“期初+入库−使用−退回=期末”的
--     恒等校验，不设数量阈值；差异必须人工查明后重新日结（append-only 保留两次记录）。
--   - **专册/交接与 HIS 处方号的对应关系【待确认】**：本期只保存处方号文本，不校验处方
--     真伪与开方医师资质（属 HIS/处方审核域）；系统不替代临床决策（AGENTS H-06）。
--   - **药房适配器 EXT-02 为 SIMULATED**：不直连药房生产库，不写入外部系统；
--     适配器失败只记录到 pharmacy_adapter_log 并降级为“本地字典可用”，不阻塞临床记录。
--   - **脱敏**：五专表只存使用人姓名摘要（≤64，服务端截断）与处方号，不含患者姓名、
--     院内患者号、身份证号、手机号；审计 detail 不含处方号原文与人员姓名。
--   - **数据范围**：五专表带 department_id，读写在服务层按调用者 m0 数据范围（ALL/SITE/DEPT）
--     限制；SELF/ROOM 不适用于五专（403），不定义新的数据范围模型。
--
-- 运行时权限要求：append-only 与平账守卫依赖触发器与函数，应用运行账号不得拥有 DDL 权限
-- （CREATE/ALTER/DROP），否则可先 ALTER TABLE ... DISABLE TRIGGER 再改写五专留痕；
-- 迁移使用独立 DDL 账号，运行账号仅授业务所需 DML（详见 docs/db/README.md §5.3）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.15）：
--   1) 应用回滚：下线 /v1/drugs*、/v1/narcotic* 与 /v1/drugs/pharmacy-adapter/status 端点及
--      前端药品目录/麻精五专菜单；M2 麻醉记录用药恢复为“仅双人核对 + 客户端声明”旧口径
--      （T-4 收口随应用版本回退，字典表保留不影响旧代码）。
--   2) 库层前向修复（需 DBA 审批 + 备份 + 麻精账归档，顺序不可颠倒）：
--      DROP TRIGGER IF EXISTS trg_daily_close_no_update ON m4_drug.daily_close;
--      DROP TRIGGER IF EXISTS trg_daily_close_no_delete ON m4_drug.daily_close;
--      DROP TRIGGER IF EXISTS trg_daily_close_no_truncate ON m4_drug.daily_close;
--      DROP TRIGGER IF EXISTS trg_cabinet_handover_no_update ON m4_drug.cabinet_handover;
--      DROP TRIGGER IF EXISTS trg_cabinet_handover_no_delete ON m4_drug.cabinet_handover;
--      DROP TRIGGER IF EXISTS trg_cabinet_handover_no_truncate ON m4_drug.cabinet_handover;
--      DROP TRIGGER IF EXISTS trg_narcotic_register_no_update ON m4_drug.narcotic_register;
--      DROP TRIGGER IF EXISTS trg_narcotic_register_no_delete ON m4_drug.narcotic_register;
--      DROP TRIGGER IF EXISTS trg_narcotic_register_no_truncate ON m4_drug.narcotic_register;
--      DROP TRIGGER IF EXISTS trg_drug_baseline_guard ON m4_drug.drug_baseline;
--      DROP TRIGGER IF EXISTS trg_drug_guard ON m4_drug.drug;
--      DROP FUNCTION IF EXISTS m4_drug.fn_reject_mutation();
--      DROP FUNCTION IF EXISTS m4_drug.fn_reject_truncate();
--      DROP FUNCTION IF EXISTS m4_drug.fn_drug_baseline_guard();
--      DROP FUNCTION IF EXISTS m4_drug.fn_drug_guard();
--      DROP TABLE m4_drug.pharmacy_adapter_log;
--      DROP TABLE m4_drug.daily_close;
--      DROP TABLE m4_drug.drug_baseline;
--      DROP TABLE m4_drug.cabinet_handover;
--      DROP TABLE m4_drug.narcotic_register;
--      DROP TABLE m4_drug.drug_alias;
--      DROP TABLE m4_drug.drug;
--      DROP SCHEMA IF EXISTS m4_drug;
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-000000000071',
--                                'b0000000-0000-4000-8000-000000000072',
--                                'b0000000-0000-4000-8000-000000000073');
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE role_id = 'a0000000-0000-4000-8000-000000000007';
--      DELETE FROM m0_platform.sys_role WHERE code = 'SYS_PHARMACIST';
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m4:drug:read', 'm4:drug:write', 'm4:narcotic:manage');
--      回滚前必须导出五专三表并校验行数；麻精账属法定留痕，须先归档到离线审计库，
--      生产执行须人签字（不可逆脚本）。
--   3) 五专记录不得回灌（append-only 语义）：如需恢复，按原标识/原时间重新登记，
--      并在业务说明中注明补录原因（须麻精管理员与临床确认）。
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS m4_drug;

COMMENT ON SCHEMA m4_drug IS
    'M4 药品目录与麻精五专（drug/drug_alias/narcotic_register/cabinet_handover/drug_baseline/daily_close/pharmacy_adapter_log）；其他模块只能经 m4-api 访问';

-- -----------------------------------------------------------------------------
-- 1. drug 药品主数据
--    编码/名称院内唯一；麻精标志为麻醉记录判定的唯一权威（M3 T-4 收口）。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.drug (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    code          varchar(64)   NOT NULL,
    name          varchar(200)  NOT NULL,
    generic_name  varchar(200),
    specification varchar(100)  NOT NULL,
    unit          varchar(16)   NOT NULL,
    dosage_form   varchar(32)   NOT NULL,
    category      varchar(32)   NOT NULL,
    narcotic      boolean       NOT NULL DEFAULT false,
    high_risk     boolean       NOT NULL DEFAULT false,
    status        varchar(16)   NOT NULL DEFAULT 'ACTIVE',
    remark        varchar(500),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       integer       NOT NULL DEFAULT 1,
    CONSTRAINT pk_drug PRIMARY KEY (id),
    CONSTRAINT ck_drug_code CHECK (code ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_drug_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_drug_version CHECK (version >= 1),
    CONSTRAINT ck_drug_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_drug_unit_not_blank CHECK (btrim(unit) <> '')
);

-- 编码大小写不敏感唯一（服务层先查重返回 409，唯一索引是并发兜底）。
CREATE UNIQUE INDEX uk_drug_code ON m4_drug.drug (lower(btrim(code)));
-- 名称/通用名唯一（trim + 忽略大小写）：名称是麻醉记录解析麻精属性的第一入口，
-- 归一化后唯一才能保证解析唯一；通用名可空，NULL 不参与唯一（部分唯一索引）。
CREATE UNIQUE INDEX uk_drug_name ON m4_drug.drug (lower(btrim(name)));
CREATE UNIQUE INDEX uk_drug_generic_name ON m4_drug.drug (lower(btrim(generic_name)))
    WHERE generic_name IS NOT NULL;
CREATE INDEX idx_drug_category ON m4_drug.drug (category);
CREATE INDEX idx_drug_narcotic ON m4_drug.drug (narcotic);
CREATE INDEX idx_drug_status ON m4_drug.drug (status);

COMMENT ON TABLE m4_drug.drug IS
    '药品主数据（编码/名称唯一，别名全局唯一）；narcotic 是麻醉记录麻精判定的唯一权威（M3 T-4 收口）';
COMMENT ON COLUMN m4_drug.drug.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m4_drug.drug.code IS '院内药品编码（唯一、大小写不敏感，创建后不可改）';
COMMENT ON COLUMN m4_drug.drug.name IS '药品名称（院内字典名，trim+大小写不敏感唯一；麻醉记录按名称解析麻精属性）';
COMMENT ON COLUMN m4_drug.drug.generic_name IS '通用名（可选，trim+大小写不敏感唯一；参与名称/别名解析）';
COMMENT ON COLUMN m4_drug.drug.specification IS '规格（如 50ug/1ml）';
COMMENT ON COLUMN m4_drug.drug.unit IS '单位（支/瓶/片/袋等）';
COMMENT ON COLUMN m4_drug.drug.dosage_form IS '剂型编码（院内字典，如 INJECTION/TABLET）';
COMMENT ON COLUMN m4_drug.drug.category IS '分类编码（院内药典分类；具体取值【待确认】）';
COMMENT ON COLUMN m4_drug.drug.narcotic IS
    '是否麻精药品（麻醉/精神类）：麻醉记录用药以本字段判定，客户端声明只做一致性校验';
COMMENT ON COLUMN m4_drug.drug.high_risk IS '是否高危（高警示）药品；仅提示，不替代医师判断';
COMMENT ON COLUMN m4_drug.drug.status IS '状态：ACTIVE=启用（可录入），INACTIVE=停用（只读历史）';
COMMENT ON COLUMN m4_drug.drug.remark IS '备注（可选，不含患者信息）';
COMMENT ON COLUMN m4_drug.drug.version IS '乐观锁版本号（从 1 起；更新必须回传，过期 409）';

-- 药品守卫：编码不可改、version 只增、状态不得从 INACTIVE 改回 ACTIVE 之外的异常值。
CREATE OR REPLACE FUNCTION m4_drug.fn_drug_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.code IS DISTINCT FROM OLD.code
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'm4_drug.drug identity columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '药品编码/创建留痕不可修改；停用请改 status';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'm4_drug.drug version must increase: % -> %', OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = '更新必须递增 version（乐观锁），不得静默覆盖';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION m4_drug.fn_drug_guard() IS
    '药品守卫：id/code/created_* 不可变、version 只增（55000）';

CREATE TRIGGER trg_drug_guard
    BEFORE UPDATE ON m4_drug.drug
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_drug_guard();

-- -----------------------------------------------------------------------------
-- 2. drug_alias 药品别名（同一药品内唯一 + 全局唯一）
--    T-4：麻醉记录按名称/通用名/别名解析麻精属性，别名全局唯一才能保证解析唯一。
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.drug_alias (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    drug_id    uuid         NOT NULL,
    alias      varchar(64)  NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid,
    CONSTRAINT pk_drug_alias PRIMARY KEY (id),
    CONSTRAINT fk_drug_alias_drug FOREIGN KEY (drug_id)
        REFERENCES m4_drug.drug (id) ON DELETE RESTRICT,
    CONSTRAINT ck_drug_alias_not_blank CHECK (btrim(alias) <> '')
);

CREATE UNIQUE INDEX uk_drug_alias_drug_alias
    ON m4_drug.drug_alias (drug_id, lower(btrim(alias)));
CREATE UNIQUE INDEX uk_drug_alias_alias ON m4_drug.drug_alias (lower(btrim(alias)));
CREATE INDEX idx_drug_alias_drug_id ON m4_drug.drug_alias (drug_id);

COMMENT ON TABLE m4_drug.drug_alias IS
    '药品别名：同一药品内唯一且全局唯一（lower(alias)），用于用药录入联想与麻精名称解析';
COMMENT ON COLUMN m4_drug.drug_alias.id IS '主键 UUID';
COMMENT ON COLUMN m4_drug.drug_alias.drug_id IS '所属药品（FK，ON DELETE RESTRICT）';
COMMENT ON COLUMN m4_drug.drug_alias.alias IS '别名（≤64，大小写不敏感全局唯一）';

-- -----------------------------------------------------------------------------
-- 3. narcotic_register 麻精专册登记（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.narcotic_register (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    drug_id           uuid          NOT NULL,
    drug_name         varchar(200)  NOT NULL,
    batch_no          varchar(64)   NOT NULL,
    quantity          numeric(12,2) NOT NULL,
    unit              varchar(16)   NOT NULL,
    prescription_no   varchar(64)   NOT NULL,
    used_by           varchar(64)   NOT NULL,
    department_id     uuid          NOT NULL,
    usage_at          timestamptz   NOT NULL,
    destination       varchar(100)  NOT NULL,
    first_signer_id   uuid          NOT NULL,
    first_signer_name varchar(64)   NOT NULL,
    second_signer_id  uuid          NOT NULL,
    second_signer_name varchar(64)  NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT pk_narcotic_register PRIMARY KEY (id),
    CONSTRAINT fk_narcotic_register_drug FOREIGN KEY (drug_id)
        REFERENCES m4_drug.drug (id) ON DELETE RESTRICT,
    CONSTRAINT ck_narcotic_register_quantity CHECK (quantity > 0),
    -- 双人签字必须不同人（服务层先返回 400，数据库为第二道防线）
    CONSTRAINT ck_narcotic_register_two_signers CHECK (first_signer_id <> second_signer_id),
    CONSTRAINT ck_narcotic_register_batch_not_blank CHECK (btrim(batch_no) <> ''),
    CONSTRAINT ck_narcotic_register_prescription_not_blank CHECK (btrim(prescription_no) <> '')
);

CREATE INDEX idx_narcotic_register_drug_id ON m4_drug.narcotic_register (drug_id);
CREATE INDEX idx_narcotic_register_department_id ON m4_drug.narcotic_register (department_id);
CREATE INDEX idx_narcotic_register_usage_at ON m4_drug.narcotic_register (usage_at);
CREATE INDEX idx_narcotic_register_batch_no ON m4_drug.narcotic_register (batch_no);

COMMENT ON TABLE m4_drug.narcotic_register IS
    '麻精专册登记（append-only）：药品/批号/数量/处方号/使用人/去向 + 双人签字（不同人）；更正只能新增';
COMMENT ON COLUMN m4_drug.narcotic_register.drug_id IS '药品标识（FK → m4_drug.drug）';
COMMENT ON COLUMN m4_drug.narcotic_register.drug_name IS '药品名称快照（字典名，写时固化）';
COMMENT ON COLUMN m4_drug.narcotic_register.quantity IS '数量（> 0，两位小数）';
COMMENT ON COLUMN m4_drug.narcotic_register.prescription_no IS '处方号（文本；审计不记原文）';
COMMENT ON COLUMN m4_drug.narcotic_register.used_by IS '使用人姓名摘要（≤64，不含患者标识）';
COMMENT ON COLUMN m4_drug.narcotic_register.department_id IS '科室标识（数据范围过滤依据）';
COMMENT ON COLUMN m4_drug.narcotic_register.usage_at IS '使用时间（timestamptz）';
COMMENT ON COLUMN m4_drug.narcotic_register.destination IS '去向（术中/术后镇痛等自由文本）';
COMMENT ON COLUMN m4_drug.narcotic_register.first_signer_id IS '第一签字人用户标识';
COMMENT ON COLUMN m4_drug.narcotic_register.second_signer_id IS '第二签字人用户标识（不得与第一签字人相同）';

-- -----------------------------------------------------------------------------
-- 4. cabinet_handover 专柜交接（append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.cabinet_handover (
    id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
    cabinet_code       varchar(32)  NOT NULL,
    department_id      uuid         NOT NULL,
    shift              varchar(8)   NOT NULL,
    handed_over_at     timestamptz  NOT NULL,
    handover_from_id   uuid         NOT NULL,
    handover_from_name varchar(64)  NOT NULL,
    handover_to_id     uuid         NOT NULL,
    handover_to_name   varchar(64)  NOT NULL,
    items              jsonb        NOT NULL,
    first_signer_id    uuid         NOT NULL,
    first_signer_name  varchar(64)  NOT NULL,
    second_signer_id   uuid         NOT NULL,
    second_signer_name varchar(64)  NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid,
    CONSTRAINT pk_cabinet_handover PRIMARY KEY (id),
    CONSTRAINT ck_cabinet_handover_shift CHECK (shift IN ('DAY', 'NIGHT')),
    CONSTRAINT ck_cabinet_handover_items CHECK (
        jsonb_typeof(items) = 'array' AND jsonb_array_length(items) >= 1
    ),
    CONSTRAINT ck_cabinet_handover_two_signers CHECK (first_signer_id <> second_signer_id),
    CONSTRAINT ck_cabinet_handover_participants CHECK (handover_from_id <> handover_to_id),
    CONSTRAINT ck_cabinet_handover_cabinet_not_blank CHECK (btrim(cabinet_code) <> '')
);

CREATE INDEX idx_cabinet_handover_cabinet_code ON m4_drug.cabinet_handover (cabinet_code);
CREATE INDEX idx_cabinet_handover_department_id ON m4_drug.cabinet_handover (department_id);
CREATE INDEX idx_cabinet_handover_handed_over_at ON m4_drug.cabinet_handover (handed_over_at);

COMMENT ON TABLE m4_drug.cabinet_handover IS
    '专柜交接（append-only）：班次/交班人/接班人/品种数量明细(jsonb) + 双人签字（不同人、交班≠接班）';
COMMENT ON COLUMN m4_drug.cabinet_handover.cabinet_code IS '专柜编码（如 CAB-ANES-01）';
COMMENT ON COLUMN m4_drug.cabinet_handover.shift IS '班次：DAY/NIGHT（两班制为演示口径，三班制【待确认】）';
COMMENT ON COLUMN m4_drug.cabinet_handover.items IS
    '品种数量明细 jsonb 数组：[{drugId,drugName,quantity,unit}]，至少 1 条';

-- -----------------------------------------------------------------------------
-- 5. drug_baseline 麻精基数（品种 + 柜唯一，乐观锁）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.drug_baseline (
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    drug_id       uuid          NOT NULL,
    cabinet_code  varchar(32)   NOT NULL,
    department_id uuid          NOT NULL,
    quantity      numeric(12,2) NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    uuid,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       integer       NOT NULL DEFAULT 1,
    CONSTRAINT pk_drug_baseline PRIMARY KEY (id),
    CONSTRAINT fk_drug_baseline_drug FOREIGN KEY (drug_id)
        REFERENCES m4_drug.drug (id) ON DELETE RESTRICT,
    CONSTRAINT ck_drug_baseline_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_drug_baseline_version CHECK (version >= 1)
);

CREATE UNIQUE INDEX uk_drug_baseline_drug_cabinet
    ON m4_drug.drug_baseline (drug_id, cabinet_code);
CREATE INDEX idx_drug_baseline_cabinet_code ON m4_drug.drug_baseline (cabinet_code);
CREATE INDEX idx_drug_baseline_department_id ON m4_drug.drug_baseline (department_id);

COMMENT ON TABLE m4_drug.drug_baseline IS
    '麻精基数：品种(drug_id) + 柜(cabinet_code) 唯一；数量 ≥ 0；乐观锁 version';
COMMENT ON COLUMN m4_drug.drug_baseline.quantity IS '基数数量（≥ 0，两位小数）';
COMMENT ON COLUMN m4_drug.drug_baseline.version IS '乐观锁版本号（更新必须回传，过期 409）';

-- 基数守卫：品种/柜不可改（改品种或柜请新增记录），version 只增。
CREATE OR REPLACE FUNCTION m4_drug.fn_drug_baseline_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.drug_id IS DISTINCT FROM OLD.drug_id
       OR NEW.cabinet_code IS DISTINCT FROM OLD.cabinet_code
       OR NEW.department_id IS DISTINCT FROM OLD.department_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'm4_drug.drug_baseline identity columns are immutable'
            USING ERRCODE = '55000',
                  HINT = '基数按 品种+柜 唯一且科室随柜不可改；改品种/柜/科室请新增记录并停用旧记录';
    END IF;
    IF NEW.version <= OLD.version THEN
        RAISE EXCEPTION 'm4_drug.drug_baseline version must increase: % -> %',
            OLD.version, NEW.version
            USING ERRCODE = '55000',
                  HINT = '基数更新必须递增 version（乐观锁）';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_drug_baseline_guard
    BEFORE UPDATE ON m4_drug.drug_baseline
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_drug_baseline_guard();

-- -----------------------------------------------------------------------------
-- 6. daily_close 日结/班结（平账 CHECK，append-only）
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.daily_close (
    id                uuid          NOT NULL DEFAULT gen_random_uuid(),
    close_date        date          NOT NULL,
    shift             varchar(8)    NOT NULL,
    cabinet_code      varchar(32)   NOT NULL,
    department_id     uuid          NOT NULL,
    opening_quantity  numeric(12,2) NOT NULL,
    inbound_quantity  numeric(12,2) NOT NULL,
    used_quantity     numeric(12,2) NOT NULL,
    returned_quantity numeric(12,2) NOT NULL,
    closing_quantity  numeric(12,2) NOT NULL,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT pk_daily_close PRIMARY KEY (id),
    CONSTRAINT ck_daily_close_shift CHECK (shift IN ('DAY', 'NIGHT')),
    CONSTRAINT ck_daily_close_non_negative CHECK (
        opening_quantity >= 0 AND inbound_quantity >= 0
        AND used_quantity >= 0 AND returned_quantity >= 0 AND closing_quantity >= 0
    ),
    -- 平账公式：期初 + 入库 − 使用 − 退回 = 期末（服务层先返回 409，数据库为第二道防线）
    CONSTRAINT ck_daily_close_balanced CHECK (
        opening_quantity + inbound_quantity - used_quantity - returned_quantity
        = closing_quantity
    )
);

CREATE UNIQUE INDEX uk_daily_close_cabinet_date_shift
    ON m4_drug.daily_close (cabinet_code, close_date, shift);
CREATE INDEX idx_daily_close_department_id ON m4_drug.daily_close (department_id);
CREATE INDEX idx_daily_close_close_date ON m4_drug.daily_close (close_date);

COMMENT ON TABLE m4_drug.daily_close IS
    '麻精日结/班结（append-only）：期初+入库−使用−退回=期末（CHECK 兜底）；不平不落库';
COMMENT ON COLUMN m4_drug.daily_close.close_date IS '业务日（Asia/Shanghai）';
COMMENT ON COLUMN m4_drug.daily_close.shift IS '班次：DAY/NIGHT';
COMMENT ON COLUMN m4_drug.daily_close.opening_quantity IS '期初数量';
COMMENT ON COLUMN m4_drug.daily_close.inbound_quantity IS '入库数量';
COMMENT ON COLUMN m4_drug.daily_close.used_quantity IS '使用数量';
COMMENT ON COLUMN m4_drug.daily_close.returned_quantity IS '退回数量';
COMMENT ON COLUMN m4_drug.daily_close.closing_quantity IS '期末（实盘）数量；必须满足平账公式';

-- -----------------------------------------------------------------------------
-- 7. pharmacy_adapter_log 药房适配器（EXT-02）同步留痕
-- -----------------------------------------------------------------------------
CREATE TABLE m4_drug.pharmacy_adapter_log (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    adapter_code varchar(16)  NOT NULL DEFAULT 'EXT-02',
    operation    varchar(32)  NOT NULL,
    success      boolean      NOT NULL,
    degraded     boolean      NOT NULL DEFAULT false,
    detail       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_pharmacy_adapter_log PRIMARY KEY (id),
    CONSTRAINT ck_pharmacy_adapter_log_adapter CHECK (adapter_code = 'EXT-02')
);

CREATE INDEX idx_pharmacy_adapter_log_occurred_at
    ON m4_drug.pharmacy_adapter_log (occurred_at);

COMMENT ON TABLE m4_drug.pharmacy_adapter_log IS
    '药房适配器（EXT-02）同步/降级留痕：只存状态摘要，不含外部凭据、处方明细与患者信息';
COMMENT ON COLUMN m4_drug.pharmacy_adapter_log.operation IS '操作类型（SYNC/STATUS 等）';
COMMENT ON COLUMN m4_drug.pharmacy_adapter_log.degraded IS '是否降级（本地字典继续可用）';
COMMENT ON COLUMN m4_drug.pharmacy_adapter_log.detail IS '状态摘要 jsonb（错误码/条数等，不含凭据与患者信息）';

-- -----------------------------------------------------------------------------
-- 8. append-only 守卫（专册/交接/日结）
--    UPDATE/DELETE/TRUNCATE 一律拒绝（SQLSTATE 55000），更正只能新增记录。
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION m4_drug.fn_reject_mutation()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000',
              HINT = '麻精五专留痕不得修改或删除；更正请新增记录';
END;
$$;

CREATE OR REPLACE FUNCTION m4_drug.fn_reject_truncate()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: TRUNCATE is not permitted', TG_TABLE_NAME
        USING ERRCODE = '55000',
              HINT = '麻精五专留痕不得清空；归档请走离线审计库';
END;
$$;

COMMENT ON FUNCTION m4_drug.fn_reject_mutation() IS
    'append-only 行级守卫：UPDATE/DELETE 拒绝（55000）';
COMMENT ON FUNCTION m4_drug.fn_reject_truncate() IS
    'append-only 语句级守卫：TRUNCATE 拒绝（55000）';

CREATE TRIGGER trg_narcotic_register_no_update
    BEFORE UPDATE ON m4_drug.narcotic_register
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_reject_mutation();
CREATE TRIGGER trg_narcotic_register_no_delete
    BEFORE DELETE ON m4_drug.narcotic_register
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_reject_mutation();
CREATE TRIGGER trg_narcotic_register_no_truncate
    BEFORE TRUNCATE ON m4_drug.narcotic_register
    FOR EACH STATEMENT EXECUTE FUNCTION m4_drug.fn_reject_truncate();

CREATE TRIGGER trg_cabinet_handover_no_update
    BEFORE UPDATE ON m4_drug.cabinet_handover
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_reject_mutation();
CREATE TRIGGER trg_cabinet_handover_no_delete
    BEFORE DELETE ON m4_drug.cabinet_handover
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_reject_mutation();
CREATE TRIGGER trg_cabinet_handover_no_truncate
    BEFORE TRUNCATE ON m4_drug.cabinet_handover
    FOR EACH STATEMENT EXECUTE FUNCTION m4_drug.fn_reject_truncate();

CREATE TRIGGER trg_daily_close_no_update
    BEFORE UPDATE ON m4_drug.daily_close
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_reject_mutation();
CREATE TRIGGER trg_daily_close_no_delete
    BEFORE DELETE ON m4_drug.daily_close
    FOR EACH ROW EXECUTE FUNCTION m4_drug.fn_reject_mutation();
CREATE TRIGGER trg_daily_close_no_truncate
    BEFORE TRUNCATE ON m4_drug.daily_close
    FOR EACH STATEMENT EXECUTE FUNCTION m4_drug.fn_reject_truncate();

-- -----------------------------------------------------------------------------
-- 9. 权限点与最小授权（M4-M15-01）
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000071', 'm4:drug:read', 'M4 药品目录只读', 'API',
     '/api/v1/drugs', 'GET', NULL, 140),
    ('b0000000-0000-4000-8000-000000000072', 'm4:drug:write', 'M4 药品目录维护', 'API',
     '/api/v1/drugs', 'POST', NULL, 141),
    ('b0000000-0000-4000-8000-000000000073', 'm4:narcotic:manage', 'M4 麻精五专维护', 'API',
     '/api/v1/narcotic', 'POST', NULL, 142);

-- 新增 SYNTHETIC 演示角色：药房药师（最小集：登录 + 药品目录维护 + 麻精五专）。
-- 说明：M0 的预置角色中没有药师角色，M4 药品域需要“药房维护目录与五专账”的职责分离；
-- 角色仅授予药品域权限，不含用户管理/审计查询（避免自助扩权）。
INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope) VALUES
    ('a0000000-0000-4000-8000-000000000007', 'SYS_PHARMACIST',
     '药房药师',
     'SYNTHETIC 演示角色：药品目录维护、麻精五专（专册/交接/基数/日结）与药房适配器状态', 'ALL')
ON CONFLICT (code) DO NOTHING;

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：运维排障与字典初始化（V2 授权早于本迁移，需显式补授）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000071'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000072'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000073'),
    -- SYS_PHARMACIST（新）：登录 + 药品目录读写 + 麻精五专
    ('a0000000-0000-4000-8000-000000000007', 'b0000000-0000-4000-8000-000000000001'),
    ('a0000000-0000-4000-8000-000000000007', 'b0000000-0000-4000-8000-000000000071'),
    ('a0000000-0000-4000-8000-000000000007', 'b0000000-0000-4000-8000-000000000072'),
    ('a0000000-0000-4000-8000-000000000007', 'b0000000-0000-4000-8000-000000000073'),
    -- SYS_ANESTHESIOLOGIST：只读目录 + 五专（临床使用/核对登记）；不授予目录维护，避免自建药品
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000071'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000073'),
    -- SYS_NURSE_ANESTHETIST：麻醉记录用药需字典联想/麻精判定，仅授予目录只读（不含五专维护）
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-000000000071');

-- -----------------------------------------------------------------------------
-- 10. 演示药品不在生产迁移路径
--     舒芬太尼/丙泊酚/吗啡 3 条 SYNTHETIC 演示药品及其别名已移至独立演示 location：
--     db/seed/demo/V101__demo_drug_synthetic.sql（仅 dev/test/uat 显式加载）。
--     生产 locations 只含 db/migration，迁移后 m4_drug.drug 为空，由药房维护真实目录。
--     麻精标志仍以本字典为唯一权威（M3 T-4 收口）。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 11. 迁移自检：表/函数/权限与最小授权必须齐全（fail-fast，避免半套 schema 上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_tables integer;
    missing_permissions integer;
    missing_grants integer;
BEGIN
    SELECT count(*) INTO missing_tables
      FROM (VALUES
            ('m4_drug.drug'), ('m4_drug.drug_alias'), ('m4_drug.narcotic_register'),
            ('m4_drug.cabinet_handover'), ('m4_drug.drug_baseline'),
            ('m4_drug.daily_close'), ('m4_drug.pharmacy_adapter_log')) AS t(name)
     WHERE to_regclass(t.name) IS NULL;
    IF missing_tables > 0 THEN
        RAISE EXCEPTION 'V20 自检失败：缺少 % 张表', missing_tables;
    END IF;

    SELECT count(*) INTO missing_permissions
      FROM (VALUES ('m4:drug:read'), ('m4:drug:write'), ('m4:narcotic:manage')) AS p(code)
     WHERE NOT EXISTS (
         SELECT 1 FROM m0_platform.sys_permission sp WHERE sp.code = p.code
     );
    IF missing_permissions > 0 THEN
        RAISE EXCEPTION 'V20 自检失败：缺少 % 个权限点', missing_permissions;
    END IF;

    SELECT count(*) INTO missing_grants
      FROM (VALUES
            ('a0000000-0000-4000-8000-000000000001', 'm4:drug:read'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:drug:write'),
            ('a0000000-0000-4000-8000-000000000001', 'm4:narcotic:manage'),
            ('a0000000-0000-4000-8000-000000000007', 'm4:drug:read'),
            ('a0000000-0000-4000-8000-000000000007', 'm4:drug:write'),
            ('a0000000-0000-4000-8000-000000000007', 'm4:narcotic:manage'),
            ('a0000000-0000-4000-8000-000000000002', 'm4:drug:read'),
            ('a0000000-0000-4000-8000-000000000002', 'm4:narcotic:manage'),
            ('a0000000-0000-4000-8000-000000000003', 'm4:drug:read')) AS g(role_id, code)
     WHERE NOT EXISTS (
         SELECT 1
           FROM m0_platform.sys_role_permission rp
           JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
          WHERE rp.role_id = g.role_id::uuid AND sp.code = g.code
     );
    IF missing_grants > 0 THEN
        RAISE EXCEPTION 'V20 自检失败：缺少 % 条最小授权', missing_grants;
    END IF;
END;
$$;
