-- =============================================================================
-- ANESOP 数据库迁移 V11：M2 知情同意与三方核查（M2-M5-01）
--
-- 设计依据：
--   方案书 §2.4.3（知情同意、三方核查/Time-out、锁单前置条件）、§2.3（围术期主流程）、
--   §3.4.2（麻醉记录单与核查实体）、§3.4.3（审计字段/乐观锁/逻辑删除）、
--   §3.9.2（RBAC/数据权限）、§3.9.4（CA 签名，EXT-05：CA 不可用，本期为占位/自签哈希）
--   契约：packages/contracts/openapi/anesthesia.yaml 0.4.0
--   任务卡：docs/ai/tasks/M2-backlog.md M2-M5-01（知情同意与三方核查）
--
-- 内容（expand 段：只新增表/索引/权限点/模板参考数据，不改写 V1–V10 既有列与数据）：
--   1) m2_anes.consent_template：知情同意模板与版本。内容不可变，新版本另建
--      (template_code, version)，append-only 触发器拒绝内容 UPDATE/DELETE/TRUNCATE；
--      仅允许一次 ACTIVE → RETIRED（停用）状态变更，停用后不再用于新签署，历史记录
--      仍引用旧版本。本期只写入 SYNTHETIC 演示模板，真实院方模板文本须医务处审核后
--      以新版本写入（【待确认】，见 docs/modules/m2/README.md §11）。
--   2) m2_anes.consent_record：同意记录与签署状态机
--      DRAFT/WAITING_SIGN → SIGNED | DECLINED；SIGNED → REVOKED（撤销须填原因）。
--      同一申请同时最多一条 status='SIGNED'（部分唯一索引 uk_consent_record_signed_application
--      强制），即“当前有效同意”；DECLINED/REVOKED 不占位。签署人姓名/关系/方式在所有状态
--      不可变（触发器守卫）。
--      CA 不可用（EXT-05）：签署只生成 signature_ref（占位标识）与 signature_hash
--      （模板内容 + 同意元数据的 SHA-256 摘要）+ signed_at，ca_status=SIMULATED；
--      不保存任何签名原文/图片/证书链。签名不可改写（触发器 + 应用状态机双保险）。
--   3) m2_anes.three_way_check：手术申请维度的三方核查（Time-out / Sign-out）。
--      append-only：记录不可 UPDATE/DELETE/TRUNCATE，只能新增 check_version；
--      最新版本同时含 time_out_at 与 sign_out_at 视为“核查完成”，供 M3 锁单前置校验
--      （M3 通过 m2-api 的 isThreeWayCheckCompleted(applicationId) 读取，不直接读表）。
--      记录单（record）维度既有契约 ThreeWayCheck 保持不变，其实现留在 M3。
--   4) 权限点 m2:consent:read|write、m2:threeway:read|write（三段式编码）：授予
--      SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST（签署与核查执行人）。
--
-- 临床口径与安全约定：
--   - 模板内容不可变，患者签署的是“某个模板的某个版本” (template_id, template_version)，
--     记录冻结版本号，模板后续新增版本不影响已签记录。
--   - 同意记录不含患者姓名以外的直接标识：signer_name 只保存签署人姓名（必要时为法定代理人），
--     不保存证件号、手机号、签名图片；签名摘要输入不含姓名（避免小空间哈希可逆）。
--   - 三方核查 issues 只保存问题项描述（jsonb 字符串数组），审计 detail 只记数量不记原文。
--   - 不物理删除：consent_template/three_way_check 永久 append-only；consent_record
--     逻辑删除列保留但本期不提供删除接口（临床留痕）。
--
-- 迁移纪律：本脚本已合入后禁止修改；变更一律新增 V<版本> 脚本（expand-contract）。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.6）：
--   1) 应用回滚：下线 /v1/anesthesia/consent-templates、/v1/anesthesia/consents*、
--      /v1/anesthesia/applications/{applicationId}/three-way-checks 路由与前端
--      知情同意/三方核查页；术前访视、申请与排程不受影响。
--   2) 库层前向修复（需 DBA 审批 + 备份，顺序不可颠倒）：
--      DROP TABLE m2_anes.three_way_check;
--      DROP TABLE m2_anes.consent_record;
--      DROP TABLE m2_anes.consent_template;
--      DROP FUNCTION IF EXISTS m2_anes.fn_consent_template_append_only();
--      DROP FUNCTION IF EXISTS m2_anes.fn_consent_record_guard();
--      DROP FUNCTION IF EXISTS m2_anes.fn_three_way_check_append_only();
--      DELETE FROM m0_platform.sys_role_permission
--        WHERE permission_id IN ('b0000000-0000-4000-8000-00000000002c',
--                                'b0000000-0000-4000-8000-00000000002d',
--                                'b0000000-0000-4000-8000-00000000002e',
--                                'b0000000-0000-4000-8000-00000000002f');
--      DELETE FROM m0_platform.sys_permission
--        WHERE code IN ('m2:consent:read', 'm2:consent:write',
--                       'm2:threeway:read', 'm2:threeway:write');
--   3) 回滚前导出 consent_template / consent_record / three_way_check 并校验行数；
--      同意与核查记录属临床留痕，须先归档到离线审计库，生产执行须人签字。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. consent_template 知情同意模板与版本（内容不可变）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.consent_template (
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    template_code varchar(64) NOT NULL,
    version      integer      NOT NULL,
    title        varchar(200) NOT NULL,
    content_text text         NOT NULL,
    effective_at timestamptz  NOT NULL,
    status       varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    retired_at   timestamptz,
    retired_by   uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   uuid,
    CONSTRAINT pk_consent_template PRIMARY KEY (id),
    -- 模板版本唯一：同一 template_code 的每个版本只允许一条记录。
    CONSTRAINT uk_consent_template_code_version UNIQUE (template_code, version),
    CONSTRAINT ck_consent_template_version CHECK (version >= 1),
    CONSTRAINT ck_consent_template_code_length CHECK (char_length(template_code) BETWEEN 1 AND 64),
    CONSTRAINT ck_consent_template_title_length CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_consent_template_content_length
        CHECK (char_length(content_text) BETWEEN 1 AND 20000),
    -- 模板状态：ACTIVE 可用于新签署；RETIRED 已停用，仅保留历史引用。
    CONSTRAINT ck_consent_template_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    -- 状态与停用留痕一致：ACTIVE 无停用时间；RETIRED 必须有停用时间。
    CONSTRAINT ck_consent_template_retired CHECK (
        (status = 'ACTIVE' AND retired_at IS NULL AND retired_by IS NULL)
        OR (status = 'RETIRED' AND retired_at IS NOT NULL)
    )
);

CREATE INDEX idx_consent_template_code ON m2_anes.consent_template (template_code);
CREATE INDEX idx_consent_template_effective_at ON m2_anes.consent_template (effective_at);
CREATE INDEX idx_consent_template_status ON m2_anes.consent_template (status);

COMMENT ON TABLE m2_anes.consent_template IS
    '知情同意模板与版本：内容不可变（append-only），新版本另建 (template_code, version)；本期为 SYNTHETIC 演示模板';
COMMENT ON COLUMN m2_anes.consent_template.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.consent_template.template_code IS '模板编码（业务唯一键的一部分，如 ANES_GENERAL）';
COMMENT ON COLUMN m2_anes.consent_template.version IS '模板版本号（业务版本，从 1 起；不是乐观锁）';
COMMENT ON COLUMN m2_anes.consent_template.title IS '模板标题（中文）';
COMMENT ON COLUMN m2_anes.consent_template.content_text IS
    '模板正文（患者签署的告知文本；不可修改；本期为演示文本，真实文本须医务处审核后新版本写入）';
COMMENT ON COLUMN m2_anes.consent_template.effective_at IS '生效时间（早于该时间的版本不用于新签署）';
COMMENT ON COLUMN m2_anes.consent_template.status IS
    '模板状态：ACTIVE=可选用，RETIRED=已停用（不可用于新签署，历史记录仍引用旧版本）';
COMMENT ON COLUMN m2_anes.consent_template.retired_at IS '停用时间；仅 RETIRED 有值';
COMMENT ON COLUMN m2_anes.consent_template.retired_by IS '停用操作人用户标识；系统停用可为 NULL';
COMMENT ON COLUMN m2_anes.consent_template.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m2_anes.consent_template.created_by IS '创建人用户标识；系统种子为 NULL';
COMMENT ON COLUMN m2_anes.consent_template.updated_at IS '记录写入时间（append-only，恒等于 created_at）';
COMMENT ON COLUMN m2_anes.consent_template.updated_by IS '记录写入人（append-only，恒等于 created_by）';

-- 模板内容不可变：身份/内容列（template_code/version/title/content_text/effective_at/
-- created_at/created_by）任何 UPDATE 拒绝；DELETE/TRUNCATE 一律拒绝。
-- 唯一允许的 UPDATE 是 ACTIVE → RETIRED（停用一次，写 retired_at/retired_by/updated_*），
-- 停用后终态不可回退或再次修改。SQLSTATE 55000 与 V1/V9/V10 的 append-only 约定一致。
CREATE OR REPLACE FUNCTION m2_anes.fn_consent_template_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.template_code IS DISTINCT FROM OLD.template_code
           OR NEW.version IS DISTINCT FROM OLD.version
           OR NEW.title IS DISTINCT FROM OLD.title
           OR NEW.content_text IS DISTINCT FROM OLD.content_text
           OR NEW.effective_at IS DISTINCT FROM OLD.effective_at
           OR NEW.created_at IS DISTINCT FROM OLD.created_at
           OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
            RAISE EXCEPTION
                'm2_anes.consent_template is append-only: content columns are immutable'
                USING ERRCODE = '55000',
                      HINT = '同意模板内容不可修改；新版本请新增 (template_code, version) 记录';
        END IF;
        IF OLD.status = 'RETIRED' THEN
            RAISE EXCEPTION
                'm2_anes.consent_template is RETIRED: UPDATE is not permitted'
                USING ERRCODE = '55000',
                      HINT = '已停用模板不可恢复或再次修改';
        END IF;
        IF NOT (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED') THEN
            RAISE EXCEPTION
                'm2_anes.consent_template only ACTIVE -> RETIRED is permitted'
                USING ERRCODE = '55000',
                      HINT = '模板仅允许停用一次；内容修改请新增版本';
        END IF;
        IF NEW.retired_at IS NULL THEN
            RAISE EXCEPTION
                'm2_anes.consent_template retire requires retired_at'
                USING ERRCODE = '55000',
                      HINT = '停用模板必须记录停用时间';
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION
        'm2_anes.consent_template is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '同意模板不得物理删除；停用请走 retire';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_consent_template_append_only() IS
    '同意模板不可变触发器函数：内容列不可改，仅允许 ACTIVE→RETIRED 一次；拒绝 DELETE/TRUNCATE（55000）';

CREATE TRIGGER trg_consent_template_no_update
    BEFORE UPDATE ON m2_anes.consent_template
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_consent_template_append_only();

CREATE TRIGGER trg_consent_template_no_delete
    BEFORE DELETE ON m2_anes.consent_template
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_consent_template_append_only();

CREATE TRIGGER trg_consent_template_no_truncate
    BEFORE TRUNCATE ON m2_anes.consent_template
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_consent_template_append_only();

-- -----------------------------------------------------------------------------
-- 1.1 演示模板（SYNTHETIC）：仅用于开发/联调与界面演示，非院方正式知情同意书。
--     真实模板须经医务处/法务审核后以新 (template_code, version) 写入；
--     ANES_GENERAL 保留 v1/v2 两版用于验证“版本不可变 + 新版本另建”。
-- -----------------------------------------------------------------------------
INSERT INTO m2_anes.consent_template
    (id, template_code, version, title, content_text, effective_at, created_at, updated_at)
VALUES
    ('b1000000-0000-4000-8000-000000000001', 'ANES_GENERAL', 1,
     '麻醉知情同意书（演示模板 v1）',
     $consent$【SYNTHETIC 演示模板，非院方正式文本】
一、拟施麻醉方式：全身麻醉（具体方案由麻醉医师根据病情确定）。
二、麻醉风险告知：可能出现药物过敏、呼吸循环抑制、恶心呕吐、咽喉不适、术后认知改变等；
    极少数情况下可能发生心脑血管意外、恶性高热等严重并发症。
三、患者/法定代理人已了解麻醉目的、方式、替代方案与主要风险，同意实施麻醉。
四、本同意书仅覆盖本次手术相关麻醉；变更麻醉方式需再次告知。
五、患者有权在麻醉实施前随时书面撤回同意。$consent$,
     TIMESTAMPTZ '2025-01-01 00:00:00+08',
     TIMESTAMPTZ '2026-11-13 00:00:00+08', TIMESTAMPTZ '2026-11-13 00:00:00+08'),
    ('b1000000-0000-4000-8000-000000000002', 'ANES_GENERAL', 2,
     '麻醉知情同意书（演示模板 v2）',
     $consent$【SYNTHETIC 演示模板，非院方正式文本】
一、拟施麻醉方式：全身麻醉（含气管插管/喉罩，具体方案由麻醉医师根据病情确定）。
二、麻醉风险告知：可能出现药物过敏、呼吸循环抑制、反流误吸、术后恶心呕吐、咽喉不适、
    术后认知改变等；极少数情况下可能发生心脑血管意外、恶性高热等严重并发症。
三、术中可能根据实际情况调整麻醉方式或加用有创监测，麻醉医师将及时告知。
四、患者/法定代理人已了解麻醉目的、方式、替代方案与主要风险，同意实施麻醉。
五、患者有权在麻醉实施前随时书面撤回同意。
六、本模板新增“术中知晓与术后镇痛”告知条款（v1 → v2 变更示例）。$consent$,
     TIMESTAMPTZ '2026-11-01 00:00:00+08',
     TIMESTAMPTZ '2026-11-13 00:00:00+08', TIMESTAMPTZ '2026-11-13 00:00:00+08'),
    ('b1000000-0000-4000-8000-000000000003', 'ANES_SEDATION', 1,
     '镇静/监护麻醉知情同意书（演示模板 v1）',
     $consent$【SYNTHETIC 演示模板，非院方正式文本】
一、拟施麻醉方式：镇静/监护麻醉（MAC），由麻醉医师持续监测生命体征。
二、风险告知：可能出现镇静过深、呼吸抑制、血压下降、恶心呕吐、躁动等；
    极少数情况下需改为全身麻醉或气管插管。
三、患者/法定代理人已了解目的、方式、替代方案与主要风险，同意实施。
四、患者有权在实施前随时书面撤回同意。$consent$,
     TIMESTAMPTZ '2025-06-01 00:00:00+08',
     TIMESTAMPTZ '2026-11-13 00:00:00+08', TIMESTAMPTZ '2026-11-13 00:00:00+08');

-- -----------------------------------------------------------------------------
-- 2. consent_record 知情同意记录与签署状态机
--    DRAFT（预留：纸质同意书回填草稿）→ WAITING_SIGN → SIGNED；
--    WAITING_SIGN → DECLINED；SIGNED → REVOKED（撤销须填原因）。
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.consent_record (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    application_id   uuid        NOT NULL,
    patient_id       uuid        NOT NULL,
    template_id      uuid        NOT NULL,
    template_version integer     NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'WAITING_SIGN',
    signer_name      varchar(64),
    signer_relation  varchar(32),
    sign_method      varchar(16),
    signed_at        timestamptz,
    signed_by        uuid,
    signature_ref    varchar(128),
    signature_hash   varchar(64),
    ca_status        varchar(16) NOT NULL DEFAULT 'PENDING',
    decline_reason   varchar(500),
    declined_at      timestamptz,
    revoked_at       timestamptz,
    revoked_by       uuid,
    revoke_reason    varchar(500),
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,
    version          bigint      NOT NULL DEFAULT 1,
    deleted          boolean     NOT NULL DEFAULT false,
    CONSTRAINT pk_consent_record PRIMARY KEY (id),
    CONSTRAINT fk_consent_record_application
        FOREIGN KEY (application_id) REFERENCES m1_sched.surgery_application (id),
    CONSTRAINT fk_consent_record_template
        FOREIGN KEY (template_id) REFERENCES m2_anes.consent_template (id),
    CONSTRAINT ck_consent_record_status
        CHECK (status IN ('DRAFT', 'WAITING_SIGN', 'SIGNED', 'DECLINED', 'REVOKED')),
    CONSTRAINT ck_consent_record_signer_relation
        CHECK (signer_relation IS NULL
            OR signer_relation IN ('SELF', 'SPOUSE', 'PARENT', 'CHILD', 'LEGAL_GUARDIAN', 'OTHER')),
    CONSTRAINT ck_consent_record_sign_method
        CHECK (sign_method IS NULL OR sign_method IN ('PAPER', 'ELECTRONIC')),
    CONSTRAINT ck_consent_record_ca_status
        CHECK (ca_status IN ('PENDING', 'SIMULATED', 'VERIFIED', 'FAILED')),
    CONSTRAINT ck_consent_record_version CHECK (version >= 1),
    CONSTRAINT ck_consent_record_template_version CHECK (template_version >= 1),
    CONSTRAINT ck_consent_record_signature_hash
        CHECK (signature_hash IS NULL OR signature_hash ~ '^[0-9a-f]{64}$'),
    -- 状态与字段一致性：避免“已签署但没有签名/签署人”“已撤销但没有原因”等不可解释状态。
    CONSTRAINT ck_consent_record_status_fields CHECK (
        (status IN ('DRAFT', 'WAITING_SIGN')
            AND signed_at IS NULL AND signed_by IS NULL
            AND signature_ref IS NULL AND signature_hash IS NULL
            AND decline_reason IS NULL AND declined_at IS NULL
            AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (status = 'SIGNED'
            AND signed_at IS NOT NULL AND signed_by IS NOT NULL
            AND signature_ref IS NOT NULL AND signature_hash IS NOT NULL
            AND ca_status IN ('SIMULATED', 'VERIFIED')
            AND decline_reason IS NULL AND declined_at IS NULL
            AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (status = 'DECLINED'
            AND decline_reason IS NOT NULL AND declined_at IS NOT NULL
            AND signed_at IS NULL AND signed_by IS NULL
            AND signature_ref IS NULL AND signature_hash IS NULL
            AND revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
        OR (status = 'REVOKED'
            AND revoke_reason IS NOT NULL AND revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND signed_at IS NOT NULL AND signed_by IS NOT NULL
            AND signature_ref IS NOT NULL AND signature_hash IS NOT NULL
            AND decline_reason IS NULL AND declined_at IS NULL)
    )
);

-- 同一申请 + 同一模板版本同时最多一条有效同意（WAITING_SIGN/SIGNED）；
-- 拒绝/撤销后索引释放，允许重新知情同意（新记录，保留原记录留痕）。
CREATE UNIQUE INDEX uk_consent_record_active
    ON m2_anes.consent_record (application_id, template_id, template_version)
    WHERE status IN ('WAITING_SIGN', 'SIGNED') AND deleted = false;

-- “当前有效同意”定义：同一申请同时最多一条 status='SIGNED' 的记录。
-- DECLINED/REVOKED 不占位；撤销后重新签署（新记录）可占用该唯一槽位。
-- 下游（M3 锁单/术前核对）取该申请 signed_at 最新的 SIGNED 记录作为“最新有效 SIGNED”，
-- 不得取 WAITING_SIGN/DECLINED/REVOKED；重复签署未撤销同意由本索引兜底（服务层 409）。
CREATE UNIQUE INDEX uk_consent_record_signed_application
    ON m2_anes.consent_record (application_id)
    WHERE status = 'SIGNED' AND deleted = false;

CREATE INDEX idx_consent_record_application_id ON m2_anes.consent_record (application_id);
CREATE INDEX idx_consent_record_patient_id ON m2_anes.consent_record (patient_id);
CREATE INDEX idx_consent_record_template_id ON m2_anes.consent_record (template_id);
CREATE INDEX idx_consent_record_status ON m2_anes.consent_record (status);
CREATE INDEX idx_consent_record_created_at ON m2_anes.consent_record (created_at);
CREATE INDEX idx_consent_record_signed_at ON m2_anes.consent_record (application_id, signed_at DESC)
    WHERE status = 'SIGNED' AND deleted = false;
CREATE INDEX idx_consent_record_deleted ON m2_anes.consent_record (deleted);

COMMENT ON TABLE m2_anes.consent_record IS
    '知情同意记录：模板版本冻结、签署状态机、CA 占位签名（EXT-05），已签署/已拒绝/已撤销不可改写';
COMMENT ON COLUMN m2_anes.consent_record.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.consent_record.application_id IS
    '关联手术申请（物理 FK；数据范围沿申请可见性，跨科室/越界返回 404）';
COMMENT ON COLUMN m2_anes.consent_record.patient_id IS '患者标识（取申请快照，不存证件号/手机号）';
COMMENT ON COLUMN m2_anes.consent_record.template_id IS '同意模板标识（FK consent_template）';
COMMENT ON COLUMN m2_anes.consent_record.template_version IS
    '签署时的模板版本号（冻结，模板新增版本不影响已签记录）';
COMMENT ON COLUMN m2_anes.consent_record.status IS
    '状态：DRAFT=预留草稿，WAITING_SIGN=待签署，SIGNED=已签署，DECLINED=已拒绝，REVOKED=已撤销';
COMMENT ON COLUMN m2_anes.consent_record.signer_name IS
    '签署人姓名（患者本人或法定代理人；只落库，不写入日志/审计）';
COMMENT ON COLUMN m2_anes.consent_record.signer_relation IS
    '签署人关系：SELF/SPOUSE/PARENT/CHILD/LEGAL_GUARDIAN/OTHER';
COMMENT ON COLUMN m2_anes.consent_record.sign_method IS '签署方式：PAPER=纸质，ELECTRONIC=电子签署（当前 CA 为占位）';
COMMENT ON COLUMN m2_anes.consent_record.signed_at IS '签署完成时间（服务端时间）；未签署为 NULL';
COMMENT ON COLUMN m2_anes.consent_record.signed_by IS '签署操作人用户标识；未签署为 NULL';
COMMENT ON COLUMN m2_anes.consent_record.signature_ref IS
    '签名占位标识（EXT-05：SIM-CA-<uuid>）；不保存签名原文/图片/证书链';
COMMENT ON COLUMN m2_anes.consent_record.signature_hash IS
    '签名摘要（SHA-256 十六进制，64 字符）：输入为同意元数据 + 模板正文摘要，不含姓名等 PHI';
COMMENT ON COLUMN m2_anes.consent_record.ca_status IS
    'CA 状态：PENDING/SIMULATED（占位签名）/VERIFIED（真实 CA，EXT-05 解除后）/FAILED';
COMMENT ON COLUMN m2_anes.consent_record.decline_reason IS
    '拒绝原因（≤500 字符；只落库，不写入日志/审计原文）';
COMMENT ON COLUMN m2_anes.consent_record.declined_at IS '拒绝时间；仅 DECLINED 有值';
COMMENT ON COLUMN m2_anes.consent_record.revoked_at IS '撤销时间；仅 REVOKED 有值';
COMMENT ON COLUMN m2_anes.consent_record.revoked_by IS '撤销操作人用户标识；仅 REVOKED 有值';
COMMENT ON COLUMN m2_anes.consent_record.revoke_reason IS
    '撤销原因（必填，≤500 字符；只落库，不写入日志/审计原文）';
COMMENT ON COLUMN m2_anes.consent_record.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m2_anes.consent_record.created_by IS '创建人用户标识';
COMMENT ON COLUMN m2_anes.consent_record.updated_at IS '最后更新时间（timestamptz）';
COMMENT ON COLUMN m2_anes.consent_record.updated_by IS '最后修改人用户标识';
COMMENT ON COLUMN m2_anes.consent_record.version IS '乐观锁版本号（从 1 起；状态变更必须回传）';
COMMENT ON COLUMN m2_anes.consent_record.deleted IS
    '逻辑删除标记（本期不提供删除接口；临床留痕不得物理删除）';

-- 同意记录守卫（第二道防线）：
--   * application/patient/template 身份列不可改；
--   * signer_name/signer_relation/sign_method 在所有状态下不可改（签署人留痕，防止
--     “签署后换人/换关系/换方式”绕过审计）；如需更正只能新建记录；
--   * DECLINED/REVOKED 终态不可再改；
--   * SIGNED 只允许“撤销”一次（必须带原因/操作人/时间），签名留痕列不可改写；
--   * DELETE/TRUNCATE 一律拒绝。SQLSTATE 55000。
CREATE OR REPLACE FUNCTION m2_anes.fn_consent_record_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.application_id <> OLD.application_id
           OR NEW.patient_id <> OLD.patient_id
           OR NEW.template_id <> OLD.template_id
           OR NEW.template_version <> OLD.template_version THEN
            RAISE EXCEPTION
                'm2_anes.consent_record identity columns are immutable'
                USING ERRCODE = '55000',
                      HINT = '同意记录的患者/申请/模板版本不可修改；请新建记录';
        END IF;
        -- 签署人留痕在所有状态（WAITING_SIGN/SIGNED/DECLINED/REVOKED）均不可变。
        IF NEW.signer_name IS DISTINCT FROM OLD.signer_name
           OR NEW.signer_relation IS DISTINCT FROM OLD.signer_relation
           OR NEW.sign_method IS DISTINCT FROM OLD.sign_method THEN
            RAISE EXCEPTION
                'm2_anes.consent_record signer fields are immutable'
                USING ERRCODE = '55000',
                      HINT = '签署人姓名/关系/方式不可改写；如需更正请新建记录';
        END IF;
        IF OLD.status IN ('DECLINED', 'REVOKED') THEN
            RAISE EXCEPTION
                'm2_anes.consent_record is terminal (%): UPDATE is not permitted', OLD.status
                USING ERRCODE = '55000',
                      HINT = '已拒绝/已撤销的同意记录不可再修改';
        END IF;
        IF OLD.status = 'SIGNED' AND NEW.status <> 'REVOKED' THEN
            RAISE EXCEPTION
                'm2_anes.consent_record is SIGNED: UPDATE to % is not permitted', NEW.status
                USING ERRCODE = '55000',
                      HINT = '已签署的同意记录不可修改；撤销请走 revoke 并填写原因';
        END IF;
        IF OLD.status = 'SIGNED' THEN
            IF NEW.revoked_at IS NULL OR NEW.revoked_by IS NULL OR NEW.revoke_reason IS NULL THEN
                RAISE EXCEPTION
                    'm2_anes.consent_record revoke requires reason/actor/time'
                    USING ERRCODE = '55000',
                          HINT = '撤销已签署的同意必须记录原因、操作人与时间';
            END IF;
            IF NEW.signed_at IS DISTINCT FROM OLD.signed_at
               OR NEW.signed_by IS DISTINCT FROM OLD.signed_by
               OR NEW.signature_ref IS DISTINCT FROM OLD.signature_ref
               OR NEW.signature_hash IS DISTINCT FROM OLD.signature_hash
               OR NEW.ca_status IS DISTINCT FROM OLD.ca_status THEN
                RAISE EXCEPTION
                    'm2_anes.consent_record signature fields are immutable once SIGNED'
                    USING ERRCODE = '55000',
                          HINT = '签名留痕（签署人/时间/摘要/CA 状态）不可改写';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION
        'm2_anes.consent_record is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '临床同意记录不得物理删除；请保留历史记录';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_consent_record_guard() IS
    '同意记录守卫：身份列与签署人留痕（姓名/关系/方式）在所有状态不可变；终态不可改；SIGNED 只能携带原因撤销；拒绝 DELETE/TRUNCATE（55000）';

CREATE TRIGGER trg_consent_record_guard_update
    BEFORE UPDATE ON m2_anes.consent_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_consent_record_guard();

CREATE TRIGGER trg_consent_record_guard_delete
    BEFORE DELETE ON m2_anes.consent_record
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_consent_record_guard();

CREATE TRIGGER trg_consent_record_guard_truncate
    BEFORE TRUNCATE ON m2_anes.consent_record
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_consent_record_guard();

-- -----------------------------------------------------------------------------
-- 3. three_way_check 手术申请维度的三方核查（append-only，版本化）
--    每次录入新增 check_version；最新版本同时有 time_out_at 与 sign_out_at 视为完成。
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.three_way_check (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    application_id   uuid        NOT NULL,
    check_version    integer     NOT NULL,
    time_out_at      timestamptz NOT NULL,
    time_out_by      uuid        NOT NULL,
    sign_out_at      timestamptz,
    sign_out_by      uuid,
    specimen_checked boolean     NOT NULL,
    implant_checked  boolean     NOT NULL,
    issues           jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    updated_by       uuid,
    CONSTRAINT pk_three_way_check PRIMARY KEY (id),
    CONSTRAINT fk_three_way_check_application
        FOREIGN KEY (application_id) REFERENCES m1_sched.surgery_application (id),
    -- 同一申请的核查版本号唯一（并发录入由唯一索引兜底，服务层映射 409）。
    CONSTRAINT uk_three_way_check_application_version UNIQUE (application_id, check_version),
    CONSTRAINT ck_three_way_check_version CHECK (check_version >= 1),
    -- Sign-out 与执行人必须成对出现；未完成 Sign-out 时两列均为 NULL。
    CONSTRAINT ck_three_way_check_sign_out
        CHECK ((sign_out_at IS NULL AND sign_out_by IS NULL)
            OR (sign_out_at IS NOT NULL AND sign_out_by IS NOT NULL)),
    -- Sign-out 不得早于 Time-out（临床时序）。
    CONSTRAINT ck_three_way_check_time_order
        CHECK (sign_out_at IS NULL OR sign_out_at >= time_out_at),
    CONSTRAINT ck_three_way_check_issues_array
        CHECK (jsonb_typeof(issues) = 'array')
);

CREATE INDEX idx_three_way_check_application_id
    ON m2_anes.three_way_check (application_id);
CREATE INDEX idx_three_way_check_created_at
    ON m2_anes.three_way_check (created_at);

COMMENT ON TABLE m2_anes.three_way_check IS
    '手术申请维度三方核查（Time-out/Sign-out）：append-only，只能新增 check_version；最新版本决定是否完成';
COMMENT ON COLUMN m2_anes.three_way_check.id IS '主键 UUID（gen_random_uuid）';
COMMENT ON COLUMN m2_anes.three_way_check.application_id IS
    '关联手术申请（FK；数据范围沿申请可见性）；记录单(record)维度核查实现留 M3';
COMMENT ON COLUMN m2_anes.three_way_check.check_version IS
    '核查版本号（同一申请从 1 递增；append-only，修正必须新增版本）';
COMMENT ON COLUMN m2_anes.three_way_check.time_out_at IS '切皮前 Time-out 完成时间';
COMMENT ON COLUMN m2_anes.three_way_check.time_out_by IS 'Time-out 执行人用户标识';
COMMENT ON COLUMN m2_anes.three_way_check.sign_out_at IS '出室前 Sign-out 完成时间；未完成为 NULL';
COMMENT ON COLUMN m2_anes.three_way_check.sign_out_by IS 'Sign-out 执行人用户标识；未完成为 NULL';
COMMENT ON COLUMN m2_anes.three_way_check.specimen_checked IS '标本核对是否一致';
COMMENT ON COLUMN m2_anes.three_way_check.implant_checked IS '植入物与耗材核对是否一致';
COMMENT ON COLUMN m2_anes.three_way_check.issues IS
    '问题项（jsonb 字符串数组，最多 20 项，每项 ≤200 字符；审计只记数量不记原文）';
COMMENT ON COLUMN m2_anes.three_way_check.created_at IS '录入时间（timestamptz）';
COMMENT ON COLUMN m2_anes.three_way_check.created_by IS '录入人用户标识';
COMMENT ON COLUMN m2_anes.three_way_check.updated_at IS '记录写入时间（append-only，恒等于 created_at）';
COMMENT ON COLUMN m2_anes.three_way_check.updated_by IS '记录写入人（append-only，恒等于 created_by）';

-- 三方核查 append-only：任何 UPDATE/DELETE/TRUNCATE 拒绝（修正 = 新增 check_version）。
CREATE OR REPLACE FUNCTION m2_anes.fn_three_way_check_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'm2_anes.three_way_check is append-only: % is not permitted', TG_OP
        USING ERRCODE = '55000',
              HINT = '三方核查记录不可修改或删除；修正请新增 check_version';
END;
$$;

COMMENT ON FUNCTION m2_anes.fn_three_way_check_append_only() IS
    '三方核查 append-only 触发器函数：拒绝 UPDATE/DELETE/TRUNCATE，SQLSTATE 55000';

CREATE TRIGGER trg_three_way_check_no_update
    BEFORE UPDATE ON m2_anes.three_way_check
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_three_way_check_append_only();

CREATE TRIGGER trg_three_way_check_no_delete
    BEFORE DELETE ON m2_anes.three_way_check
    FOR EACH ROW EXECUTE FUNCTION m2_anes.fn_three_way_check_append_only();

CREATE TRIGGER trg_three_way_check_no_truncate
    BEFORE TRUNCATE ON m2_anes.three_way_check
    FOR EACH STATEMENT EXECUTE FUNCTION m2_anes.fn_three_way_check_append_only();

-- -----------------------------------------------------------------------------
-- 4. 权限点 m2:consent:read|write、m2:threeway:read|write
--    授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST：
--    知情同意与三方核查由麻醉医师/麻醉护士执行，系统管理员用于运维与排障。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort) VALUES
    ('b0000000-0000-4000-8000-00000000002c', 'm2:consent:read', 'M2 知情同意只读', 'API',
     '/api/v1/anesthesia/consents', 'GET', NULL, 132),
    ('b0000000-0000-4000-8000-00000000002d', 'm2:consent:write', 'M2 知情同意签署与撤销', 'API',
     '/api/v1/anesthesia/consents', 'POST', NULL, 133),
    ('b0000000-0000-4000-8000-00000000002e', 'm2:threeway:read', 'M2 三方核查只读', 'API',
     '/api/v1/anesthesia/applications', 'GET', NULL, 134),
    ('b0000000-0000-4000-8000-00000000002f', 'm2:threeway:write', 'M2 三方核查录入', 'API',
     '/api/v1/anesthesia/applications', 'POST', NULL, 135);

INSERT INTO m0_platform.sys_role_permission (role_id, permission_id) VALUES
    -- SYS_ADMIN：读写（运维/排障）
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000002c'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000002d'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000002e'),
    ('a0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000002f'),
    -- SYS_ANESTHESIOLOGIST：签署与核查执行人
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000002c'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000002d'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000002e'),
    ('a0000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-00000000002f'),
    -- SYS_NURSE_ANESTHETIST：签署与核查执行人
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-00000000002c'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-00000000002d'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-00000000002e'),
    ('a0000000-0000-4000-8000-000000000003', 'b0000000-0000-4000-8000-00000000002f');
