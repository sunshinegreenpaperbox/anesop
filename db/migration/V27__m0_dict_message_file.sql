-- =============================================================================
-- ANESOP 数据库迁移 V27：M0 公共支撑补全（字典管理 / 消息中心 / 文件元数据）
-- 目标库：PostgreSQL 16；schema：m0_platform
--
-- 设计依据：
--   方案书 §2.4.1（M0 公共支撑：字典、消息通知、文件与附件）、§3.9.2（RBAC 三段式权限点）、
--   §3.9.3（敏感字段与 PHI 禁令）、§2.4.10（文件/影像存储边界）
--   契约：packages/contracts/openapi/platform.yaml 0.2.0（第 12 域）
--   ADR：docs/adr/0024-字典消息文件口径.md
--   任务：M8-M0-04（收口 docs/acceptance/FINAL-REPORT.md §6.2「M0 字典 API/消息中心/
--   文件管理」遗留）
--
-- 内容（只追加，不改写 V1–V26 既有表结构、权限与角色）：
--   1) 字典：V1 已建 sys_dict_type / sys_dict_item，且已由
--      uk_sys_dict_item_type_code (dict_type_id, code) 保证「同类型内编码唯一」——
--      本迁移**不再重复建唯一约束**，只补一个读路径排序索引
--      idx_sys_dict_item_type_sort (dict_type_id, sort, code)，供 GET /v1/dicts/{type}
--      按 sort/code 有序返回启用项；字典项停用为逻辑停用（status=0），不物理删除。
--   2) 消息：m0_platform.sys_message（仅收件人本人可见；模板编码 + 参数 jsonb 白名单；
--      read_at 记录已读；无 PHI、无临床自由文本）。索引覆盖「收件人 + 时间倒序」与
--      「未读」部分索引，支撑 GET /v1/messages?unreadOnly 与未读角标。
--   3) 文件元数据：m0_platform.sys_file（内容寻址 storage_key、大小、类型、SHA-256、
--      上传者、业务关联；**不保存客户端原始文件名**，展示名由服务端生成 file-<id>.<ext>，
--      避免姓名/住院号/身份证明文件名入库；本地 FS 适配器目录由应用配置
--      anesop.m0.file.root-dir 指定，默认仓库内 .cache/files，生产挂载盘；MinIO 适配器
--      仅接口占位 EXT-06）。
--      约束把「大小 ≤ 20 MiB」「内容类型白名单」「业务类型白名单」固化到库层，
--      与契约 platform.yaml 0.2.0 一致；删除为逻辑删除（deleted=true），
--      保留上传/下载审计可追溯的元数据。
--   4) 权限点（三段式 <模块>:<资源>:<动作>，最小授予）：
--      - m0:dict:read     字典查询（GET /v1/dicts、GET /v1/dicts/{type}）
--                         授予全部 6 个平台基线角色（字典是全院参考数据）；
--      - m0:dict:write    字典维护（POST/PUT/DELETE /v1/dicts/{type}/items）
--                         **仅授予 SYS_ADMIN**（方案书：字典维护属平台管理员职责；
--                         服务层另有 SYS_ADMIN 角色纵深校验）；
--      - m0:message:read  消息中心（GET /v1/messages、POST /v1/messages/{id}/read、
--                         POST /v1/messages/read-all），授予全部 6 个平台基线角色；
--      - m0:file:read     文件元数据/下载（GET /v1/files/{id}、/{id}/meta），
--                         授予全部 6 个平台基线角色（可见性另按业务权限收紧）；
--      - m0:file:write    文件上传（POST /v1/files），授予全部 6 个平台基线角色
--                         （临床角色需上传模板/报表/附件）。
--      V2 遗留的两段式 dict:read / dict:write 权限点**保留不动**（不改历史脚本），
--      新端点一律使用三段式权限点；遗留权限点的清理在 1.0.0 契约大版本时随
--      ADR-0011 一并处理（见 ADR-0024 §6）。
--
-- 为什么没有改 m1/m2/m4/m5 表：
--   消息与文件是 M0 公共支撑能力，不落业务模块表；文件与业务的关联只存
--   business_type + business_id（逻辑关联），不建跨 schema 外键，避免模块间数据库耦合
--   （AGENTS「禁止跨模块访问对方数据表」）。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only；本脚本可逆，详见 docs/db/README.md §4.22）：
--   1) 应用回滚：下线 /v1/dicts/*（管理写）、/v1/messages/*、/v1/files/* 与前端
--      「字典管理 / 消息中心 / 文件管理」菜单；字典读与既有业务不受影响（字典表沿用 V1）。
--   2) 库层回滚（需 DBA 审批 + 备份；仅 data-only 部分可逆，建表需 DROP）：
--      DELETE FROM m0_platform.sys_role_permission
--       WHERE permission_id IN ('b0000000-0000-4000-8000-000000000086',
--         'b0000000-0000-4000-8000-000000000087','b0000000-0000-4000-8000-000000000088',
--         'b0000000-0000-4000-8000-000000000089','b0000000-0000-4000-8000-00000000008a');
--      DELETE FROM m0_platform.sys_permission
--       WHERE code IN ('m0:dict:read','m0:dict:write','m0:message:read',
--                      'm0:file:read','m0:file:write');
--      DROP TABLE m0_platform.sys_message;
--      DROP TABLE m0_platform.sys_file;
--      DROP INDEX m0_platform.idx_sys_dict_item_type_sort;
--   3) 回滚顺序：先删授权再删权限点（sys_role_permission.permission_id 外键），
--      最后删表/索引；回滚后重新登录以刷新 JWT 权限 claim（令牌中的权限点不追溯失效）。
--   4) 文件内容不在库内：回滚前如需保留已上传文件，先备份 anesop.m0.file.root-dir 目录
--      并登记台账（文件数、SHA-256 清单、操作人、时间），再清理元数据。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 字典：补读路径排序索引（唯一约束 (dict_type_id, code) 已由 V1 建立）
-- -----------------------------------------------------------------------------
CREATE INDEX idx_sys_dict_item_type_sort
    ON m0_platform.sys_dict_item (dict_type_id, sort, code);

COMMENT ON INDEX m0_platform.idx_sys_dict_item_type_sort IS
    '字典项读路径索引：按类型取启用项并按 sort/code 有序返回（GET /v1/dicts/{type}）';

-- -----------------------------------------------------------------------------
-- 2. sys_message 站内消息
--    仅收件人本人可见；由领域事件/系统广播写入（内部服务，不暴露任意发送端点）；
--    params 只存模板参数白名单（术间号/科室名/时间/计数等），禁止患者标识与临床自由文本。
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_message (
    id                uuid         NOT NULL DEFAULT gen_random_uuid(),
    recipient_user_id uuid         NOT NULL,
    message_type      varchar(16)  NOT NULL,
    template_code     varchar(64)  NOT NULL,
    title             varchar(200) NOT NULL,
    params            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    read_at           timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid,
    CONSTRAINT pk_sys_message PRIMARY KEY (id),
    CONSTRAINT ck_sys_message_type CHECK (message_type IN
        ('SYSTEM', 'SCHEDULE', 'ALERT', 'QC', 'TASK')),
    CONSTRAINT ck_sys_message_params_object CHECK (jsonb_typeof(params) = 'object'),
    CONSTRAINT fk_sys_message_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES m0_platform.sys_user (id)
);

-- 收件人维度分页（创建时间倒序、同时间按 id 倒序）
CREATE INDEX idx_sys_message_recipient_created
    ON m0_platform.sys_message (recipient_user_id, created_at DESC, id DESC);

-- 未读角标与 unreadOnly 过滤：部分索引只覆盖未读行
CREATE INDEX idx_sys_message_unread
    ON m0_platform.sys_message (recipient_user_id)
    WHERE read_at IS NULL;

COMMENT ON TABLE m0_platform.sys_message IS
    '站内消息：仅收件人本人可见；由领域事件/系统广播写入（模板编码 + 参数白名单，无 PHI）';
COMMENT ON COLUMN m0_platform.sys_message.id IS '主键 UUID';
COMMENT ON COLUMN m0_platform.sys_message.recipient_user_id IS
    '收件人 UUID，FK → sys_user.id；查询强制 recipient_user_id = 当前用户';
COMMENT ON COLUMN m0_platform.sys_message.message_type IS
    '消息类型：SYSTEM=系统广播、SCHEDULE=排程变更、ALERT=预警提醒、QC=质控任务、TASK=待办';
COMMENT ON COLUMN m0_platform.sys_message.template_code IS
    '模板编码（如 SCHEDULE_CHANGED），前端按编码本地化文案';
COMMENT ON COLUMN m0_platform.sys_message.title IS
    '消息标题（由模板 + 参数白名单渲染，禁止患者标识与临床自由文本）';
COMMENT ON COLUMN m0_platform.sys_message.params IS
    '模板参数 jsonb（对象）：只允许 roomNo/deptName/startAt/endAt/count/url 等非 PHI 字段';
COMMENT ON COLUMN m0_platform.sys_message.read_at IS
    '已读时间（NULL=未读）；已读为幂等更新，不物理删除消息';
COMMENT ON COLUMN m0_platform.sys_message.created_at IS '创建时间（timestamptz）';
COMMENT ON COLUMN m0_platform.sys_message.created_by IS
    '写入方标识（系统广播为用户 UUID；领域事件可为 NULL）';

-- -----------------------------------------------------------------------------
-- 3. sys_file 文件元数据（内容不在库内，落本地 FS；MinIO 适配器 EXT-06 占位）
-- -----------------------------------------------------------------------------
CREATE TABLE m0_platform.sys_file (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    storage_key   varchar(255) NOT NULL,
    content_type  varchar(128) NOT NULL,
    size_bytes    bigint       NOT NULL,
    sha256        char(64)     NOT NULL,
    business_type varchar(16)  NOT NULL,
    business_id   varchar(64),
    uploader_id   uuid         NOT NULL,
    scan_status   varchar(16)  NOT NULL DEFAULT 'NOT_SCANNED',
    deleted       boolean      NOT NULL DEFAULT false,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_file PRIMARY KEY (id),
    CONSTRAINT ck_sys_file_size CHECK (size_bytes > 0 AND size_bytes <= 20971520),
    CONSTRAINT ck_sys_file_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sys_file_business_type CHECK (business_type IN
        ('TEMPLATE', 'REPORT', 'ATTACHMENT', 'SCHEDULE', 'RECORD')),
    CONSTRAINT ck_sys_file_content_type CHECK (content_type IN
        ('application/pdf', 'text/csv', 'text/plain', 'image/png', 'image/jpeg')),
    CONSTRAINT ck_sys_file_scan_status CHECK (scan_status IN
        ('NOT_SCANNED', 'CLEAN', 'INFECTED')),
    CONSTRAINT ck_sys_file_business_id CHECK (business_id IS NULL OR length(business_id) <= 64),
    CONSTRAINT fk_sys_file_uploader FOREIGN KEY (uploader_id)
        REFERENCES m0_platform.sys_user (id)
);

-- 内容寻址去重：同一 SHA-256 只落盘一份（元数据每次上传独立，便于按上传者控制可见性）
CREATE INDEX idx_sys_file_sha256 ON m0_platform.sys_file (sha256);
-- 我的文件 / 上传者维度
CREATE INDEX idx_sys_file_uploader ON m0_platform.sys_file (uploader_id, created_at DESC);
-- 业务关联（台次/记录/报表/模板）
CREATE INDEX idx_sys_file_business ON m0_platform.sys_file (business_type, business_id);

COMMENT ON TABLE m0_platform.sys_file IS
    '文件元数据：非 PHI（模板/报表/附件），内容按 SHA-256 内容寻址落本地 FS；不保存客户端原始文件名；MINIO 适配器占位 EXT-06';
COMMENT ON COLUMN m0_platform.sys_file.id IS '主键 UUID（接口 fileId）';
COMMENT ON COLUMN m0_platform.sys_file.storage_key IS
    '存储适配器相对键（内容寻址 sha256 分片路径），不出接口，防止绕过鉴权直接读盘';
COMMENT ON COLUMN m0_platform.sys_file.content_type IS
    '内容类型白名单：application/pdf、text/csv、text/plain、image/png、image/jpeg';
COMMENT ON COLUMN m0_platform.sys_file.size_bytes IS '文件字节数（1–20971520，上限 20 MiB）';
COMMENT ON COLUMN m0_platform.sys_file.sha256 IS
    '文件内容 SHA-256（服务端计算，十六进制 64 字符），用于完整性校验与内容寻址去重';
COMMENT ON COLUMN m0_platform.sys_file.business_type IS
    '业务类型：TEMPLATE=模板、REPORT=报表、ATTACHMENT=附件、SCHEDULE=排程附件、RECORD=记录附件';
COMMENT ON COLUMN m0_platform.sys_file.business_id IS
    '业务关联标识（逻辑关联，不建跨 schema 外键）；无关联为 NULL';
COMMENT ON COLUMN m0_platform.sys_file.uploader_id IS
    '上传者 UUID，FK → sys_user.id；上传者始终可下载自己的文件';
COMMENT ON COLUMN m0_platform.sys_file.scan_status IS
    '病毒扫描状态：NOT_SCANNED=未扫描（当前占位 EXT-07）、CLEAN=无威胁、INFECTED=发现威胁';
COMMENT ON COLUMN m0_platform.sys_file.deleted IS
    '逻辑删除：true=已删除（遗留内容与审计可追溯；接口按 404 处理）';
COMMENT ON COLUMN m0_platform.sys_file.created_at IS '上传时间（timestamptz）';

-- -----------------------------------------------------------------------------
-- 4. 权限点（三段式；字典读/消息/文件为全院基础能力，字典写仅平台管理员）
--    权限点 ID 必须全局唯一：不复用 V1–V26 已占用的 UUID 区间（b...80–b...85 已属
--    历史迁移），此处使用 b...86–b...8a；不使用 ON CONFLICT DO NOTHING，冲突必须让迁移
--    失败（避免“静默少授权”）。
-- -----------------------------------------------------------------------------
INSERT INTO m0_platform.sys_permission (id, code, name, type, path, http_method, parent_id, sort)
VALUES
    ('b0000000-0000-4000-8000-000000000086', 'm0:dict:read',
     'M0 字典查询', 'API', '/api/v1/dicts/*', 'GET', NULL, 160),
    ('b0000000-0000-4000-8000-000000000087', 'm0:dict:write',
     'M0 字典维护', 'API', '/api/v1/dicts/*', 'POST', NULL, 161),
    ('b0000000-0000-4000-8000-000000000088', 'm0:message:read',
     'M0 消息中心', 'API', '/api/v1/messages/*', 'GET', NULL, 162),
    ('b0000000-0000-4000-8000-000000000089', 'm0:file:read',
     'M0 文件下载', 'API', '/api/v1/files/*', 'GET', NULL, 163),
    ('b0000000-0000-4000-8000-00000000008a', 'm0:file:write',
     'M0 文件上传', 'API', '/api/v1/files', 'POST', NULL, 164);

-- 兼容：若某院方基线只建了 6 个角色中的一部分（如测试库），授权用 SELECT 过滤，
-- 避免因角色缺失导致整条 INSERT 失败（生产迁移仍只会命中既有 6 个基线角色）。
-- -----------------------------------------------------------------------------
-- 5. 角色—权限授权（最小授予）
-- -----------------------------------------------------------------------------
-- 5.1 m0:dict:read 与 m0:message:read：全部基线角色（字典/消息是全院基础能力）
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM m0_platform.sys_role r
JOIN m0_platform.sys_permission p
  ON p.code IN ('m0:dict:read', 'm0:message:read')
WHERE r.code IN ('SYS_ADMIN', 'SYS_ANESTHESIOLOGIST', 'SYS_NURSE_ANESTHETIST',
                 'SYS_SURGEON', 'SYS_OR_MANAGER', 'SYS_QC_MANAGER')
ON CONFLICT DO NOTHING;

-- 5.2 m0:dict:write：仅 SYS_ADMIN（字典维护是平台管理员职责）
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM m0_platform.sys_role r
JOIN m0_platform.sys_permission p ON p.code = 'm0:dict:write'
WHERE r.code = 'SYS_ADMIN'
ON CONFLICT DO NOTHING;

-- 5.3 m0:file:read / m0:file:write：全部基线角色
--     （可见性另由业务类型所需的模块只读权限收紧，见 ADR-0024 §4）
INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM m0_platform.sys_role r
JOIN m0_platform.sys_permission p
  ON p.code IN ('m0:file:read', 'm0:file:write')
WHERE r.code IN ('SYS_ADMIN', 'SYS_ANESTHESIOLOGIST', 'SYS_NURSE_ANESTHETIST',
                 'SYS_SURGEON', 'SYS_OR_MANAGER', 'SYS_QC_MANAGER')
ON CONFLICT DO NOTHING;
