-- =============================================================================
-- ANESOP 数据库迁移 V29：消息业务投递去重 + 体征/抢救收口（M9-01）
-- 目标库：PostgreSQL 16；schema：m0_platform（ALTER）
--
-- 设计依据：
--   方案书 §2.4.1（M0 消息通知）、§2.4.3（M2 麻醉记录/采集/抢救）、§3.9.3（PHI 禁令）
--   契约：packages/contracts/openapi/platform.yaml 0.2.1（消息模板编码说明，仅描述）、
--         packages/contracts/openapi/anesthesia.yaml 0.11.0（体征采样与抢救麻精核对）
--   任务：M9-01（收口 FINAL-REPORT §6.2「消息投递 / 记录单 vitalSigns / 抢救用药麻精判定」）
--   ADR：docs/adr/0025-消息投递与体征映射口径.md
--
-- 内容（expand 段：只新增列/索引，不改写 V1–V28 既有表结构、权限与数据）：
--   1) m0_platform.sys_message.dedupe_key varchar(200)：
--      业务事件投递去重键，唯一索引保证「同一业务事件 + 接收人 + 消息类型」只落一条；
--      可空（历史消息与系统广播无去重需求）；PostgreSQL 唯一索引允许多个 NULL，
--      因此不需要 partial index。去重键由 m1 NotificationPort 生成：
--      OUTBOX:<outboxId>:<recipientUserId>:<templateCode>，只含标识，无 PHI。
--   2) 抢救用药的第二核对人**不新增列**：drugs 为 jsonb 数组，第二核对人
--      secondCheckerId/secondCheckerName 随 ResuscitationDrug 一起存入既有
--      m2_anes.resuscitation_record.drugs，避免为可变明细逐项建列；数据库层仍由
--      append-only 触发器（V15/V17）与时间窗约束兜底。
--   3) 无新增权限点：消息读取复用 m0:message:read（V27），抢救写复用 m2:rescue:write
--      （V15 已授予），不扩大授权面。
--
-- 为什么只加一列：
--   - 消息业务投递的接收人/频率口径属院方管理口径，本期按**默认通知矩阵**实现并在
--     docs/modules/m1/README.md §通知矩阵【待确认】登记 Q；表结构只需去重键，
--     不预置频率/免打扰/升级路径字段（避免把未确认口径固化进库）。
--   - 体征映射只读 m2_anes.anesthesia_vital（V4）与 device_registry（V14），
--     不需要新表或新列；记录单时间窗由 anesthesia_record.started_at/ended_at 推导。
--
-- 迁移纪律：本脚本已合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only；本脚本可逆，详见 docs/db/README.md §4.24）：
--   1) 应用回滚：停用 m1 通知派发（anesop.m1.notification.scheduler-enabled=false）
--      并回退到仅日志投递；记录单 vitalSigns 回退为空数组占位；抢救用药不做字典判定
--      （历史 jsonb 中已写入的 secondChecker 字段保留，不删除、不改写）。
--   2) 库层回滚（需 DBA 审批 + 备份）：
--      DROP INDEX IF EXISTS m0_platform.uk_sys_message_dedupe_key;
--      ALTER TABLE m0_platform.sys_message DROP COLUMN IF EXISTS dedupe_key;
--      回滚前先导出 sys_message.dedupe_key 非空行（去重事实），确认无业务方依赖；
--      回滚不删除消息与抢救记录（append-only 留痕）。
--   3) 权限：无新增权限点，无需回收授权。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 消息业务投递去重键
-- -----------------------------------------------------------------------------
ALTER TABLE m0_platform.sys_message
    ADD COLUMN dedupe_key varchar(200);

COMMENT ON COLUMN m0_platform.sys_message.dedupe_key IS
    '业务投递去重键（OUTBOX:<outboxId>:<recipientUserId>:<templateCode>，无 PHI）；'
    '唯一索引保证同一业务事件+接收人+模板只落一条；NULL 表示不去重（系统广播/历史消息）';

-- PostgreSQL 唯一索引允许多个 NULL：无去重需求的消息不受影响。
CREATE UNIQUE INDEX uk_sys_message_dedupe_key
    ON m0_platform.sys_message (dedupe_key);

COMMENT ON INDEX m0_platform.uk_sys_message_dedupe_key IS
    '消息业务投递去重：同一业务事件+接收人+模板唯一；配合 INSERT ... ON CONFLICT DO NOTHING';

-- -----------------------------------------------------------------------------
-- 2. 迁移自检（缺列/缺索引即失败，避免半套结构上线）
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    missing_column integer;
    missing_index integer;
BEGIN
    SELECT count(*) INTO missing_column
      FROM information_schema.columns
     WHERE table_schema = 'm0_platform'
       AND table_name = 'sys_message'
       AND column_name = 'dedupe_key';
    IF missing_column = 0 THEN
        RAISE EXCEPTION 'V29 自检失败：m0_platform.sys_message 缺少 dedupe_key 列';
    END IF;

    SELECT count(*) INTO missing_index
      FROM pg_indexes
     WHERE schemaname = 'm0_platform'
       AND indexname = 'uk_sys_message_dedupe_key';
    IF missing_index = 0 THEN
        RAISE EXCEPTION 'V29 自检失败：缺少 uk_sys_message_dedupe_key 唯一索引';
    END IF;
END;
$$;
