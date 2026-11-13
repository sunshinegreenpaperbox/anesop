-- =============================================================================
-- ANESOP 数据库迁移 V16：M2 术中预警实时触发、评估水位、规则待确认与抢救人员留痕
-- 目标库：PostgreSQL 16；schema：m2_anes
--
-- 背景（M3-M2-02 独立评审 blocking B-1/B-3/B-4 与建议 S-2/S-4）：
--   V15 的预警评估为“查询时 + 手动补偿”，纯监护页面长时间无交互不会产生告警；长台次
--   体征样本按上限截断会丢失早期数据；DRAFT 记录可能误用同术间上一台次患者体征；
--   抢救记录缺少负责人/参与人员留痕；规则阈值变更缺少“待临床确认”标记。
--   本迁移只做 expand（新增列/表/索引/回填），不改写 V1–V15 既有列、约束与语义。
--
-- 内容：
--   1) m2_anes.anesthesia_record.room_id：记录单创建时经 m1-api（只读）快照术间，
--      供采集落库后的领域事件按 roomId 匹配活动记录；已有记录按台次关联回填。
--   2) m2_anes.alert_watermark：每张记录单的“已评估 measured_at 水位”，
--      实时评估只推进水位、只对水位之后的新采样判定，不回评历史窗口。
--   3) m2_anes.alert_rule.pending_confirmation：阈值/持续时长变更后的临床待确认标记；
--      V15 默认种子阈值全部标【待确认】，回填为 true。
--   4) m2_anes.alert_rule.created_by/updated_by 的默认种子回填为系统用户 UUID
--      （S-4 备选方案：契约 createdBy 保持必填，不改为可空，避免生成物与客户端破坏）。
--   5) m2_anes.resuscitation_record.leader（负责人 jsonb 对象，必填）与
--      rescuers（参与人员 jsonb 数组，≥1），append-only 语义不变。
--
-- 口径与约束（【待确认】临床口径以临床顾问确认为准）：
--   - 水位：alert_watermark.evaluated_through 为该记录单已评估到的最大 measured_at；
--     评估窗口从 max(记录 startedAt, 水位 − 回看窗口) 起读取采样，仅对 triggeredAt >
--     水位的新越界窗口落库；水位只增（GREATEST），绝不回退。
--   - 迁移前已存在的记录单：水位初始化为迁移时刻 now()，即从迁移时刻起评估，
--     不用当前规则回评历史窗口（临床安全：避免追溯性假告警）。
--   - 规则待确认：阈值或持续时长较上一版本变化时置 true；仅名称/严重度/启用状态变化的
--     新版本沿用上一版本标记。清除标记须临床顾问确认后由 M5 复核流程写入（本任务不做）。
--   - 抢救负责人/参与人员：leader 为 {userId?, name} 对象且 name 非空；rescuers 为
--     [{userId?, name}] 数组且至少 1 人；表内不存患者标识。append-only 触发器不变。
--
-- expand-contract 说明：
--   expand   只新增列/表/索引/回填与标记，旧代码（按 createdAt 兜底窗口、查询时评估、
--            无 leader/rescuers 的抢救写入）在迁移后仍可运行：room_id 可空、水位表可无行、
--            leader/rescuers 由服务层必填校验（历史行已回填）。
--   contract 删除列/改类型/改语义须新版本 + 双写 + ADR + 回滚演练。
--
-- 迁移纪律：本脚本合入后禁止修改（Flyway checksum），修正一律新增 V<N+1>。
-- 回滚策略（forward-only，详见 docs/db/README.md §4.11）：
--   1) 应用回滚：恢复 V15 时代的查询时评估与无人员字段的抢救表单；采集/记录/时间轴不受影响。
--   2) 库层前向修复（需 DBA 审批 + 备份，顺序不可颠倒）：
--      DROP TABLE m2_anes.alert_watermark;
--      ALTER TABLE m2_anes.resuscitation_record DROP CONSTRAINT ck_resuscitation_rescuers;
--      ALTER TABLE m2_anes.resuscitation_record DROP CONSTRAINT ck_resuscitation_leader;
--      ALTER TABLE m2_anes.resuscitation_record DROP COLUMN rescuers;
--      ALTER TABLE m2_anes.resuscitation_record DROP COLUMN leader;
--      DROP INDEX m2_anes.idx_anesthesia_record_room_active;
--      ALTER TABLE m2_anes.anesthesia_record DROP COLUMN room_id;
--      ALTER TABLE m2_anes.alert_rule DROP COLUMN pending_confirmation;
--      回滚前导出 alert_watermark 与 resuscitation_record 并校验行数；抢救属临床留痕，
--      须先归档到离线审计库，生产执行须人签字。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 记录单术间快照（采集事件按 roomId 匹配活动记录；不跨模块读 m1 表）
-- -----------------------------------------------------------------------------
ALTER TABLE m2_anes.anesthesia_record
    ADD COLUMN room_id uuid;

-- 回填：按台次（schedule_id）关联术间；记录单创建时由 m1-api Schedule.roomId 写入。
-- 迁移期临时关闭用户触发器：V13 记录单守卫要求 version 只增且身份列不可变，
-- 回填不改变业务语义、不递增 version（与 V15/V16 的 expand 口径一致）。
ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER;
UPDATE m2_anes.anesthesia_record r
   SET room_id = s.room_id
  FROM m1_sched.schedule s
 WHERE s.id = r.schedule_id
   AND r.room_id IS NULL;
ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER;

CREATE INDEX idx_anesthesia_record_room_active
    ON m2_anes.anesthesia_record (room_id, started_at)
    WHERE deleted = false AND status = 'ONGOING';

COMMENT ON COLUMN m2_anes.anesthesia_record.room_id IS
    '创建时经 m1-api 快照的术间标识（uuid，可空=台次未排术间）；采集落库事件按它匹配 '
    'ONGOING 记录；不跨模块读 m1_sched 表';

-- -----------------------------------------------------------------------------
-- 2. 告警评估水位（长台次增量推进，避免采样上限截断丢失早期越界窗口）
-- -----------------------------------------------------------------------------
CREATE TABLE m2_anes.alert_watermark (
    record_id         uuid        NOT NULL,
    evaluated_through timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_alert_watermark PRIMARY KEY (record_id),
    CONSTRAINT fk_alert_watermark_record FOREIGN KEY (record_id)
        REFERENCES m2_anes.anesthesia_record (id) ON DELETE RESTRICT
);

COMMENT ON TABLE m2_anes.alert_watermark IS
    '告警评估水位：每张记录单已评估到的最大 measured_at；实时评估只对水位之后的新采样'
    '判定（不回评历史窗口），水位只增';
COMMENT ON COLUMN m2_anes.alert_watermark.record_id IS '记录单标识（主键，FK anesthesia_record）';
COMMENT ON COLUMN m2_anes.alert_watermark.evaluated_through IS
    '已评估到的最大采样时间（measured_at）；评估窗口从 max(startedAt, 水位−回看) 起';
COMMENT ON COLUMN m2_anes.alert_watermark.updated_at IS '水位最近推进时间（服务端时间）';

-- 迁移前已存在的记录单：从迁移时刻起评估，不用当前规则回评历史窗口。
INSERT INTO m2_anes.alert_watermark (record_id, evaluated_through, updated_at)
SELECT id, now(), now() FROM m2_anes.anesthesia_record;

-- -----------------------------------------------------------------------------
-- 3. 规则“待临床确认”标记 + 默认种子创建人回填
--    S-4 采用备选方案：契约 createdBy 保持必填（string），种子写系统用户 UUID，
--    避免把既有必填字段改为可空造成的生成物/客户端破坏。
-- -----------------------------------------------------------------------------
ALTER TABLE m2_anes.alert_rule
    ADD COLUMN pending_confirmation boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN m2_anes.alert_rule.pending_confirmation IS
    '临床待确认标记：阈值/持续时长变更的新版本置 true；仅名称/严重度/启用变化沿用上一版本；'
    '清除须临床顾问确认后由 M5 复核流程写入';

-- 迁移期临时关闭规则守卫：V15 守卫只允许“启用→停用”，且 created_by 不可变；
-- 本次回填只补“待确认”标记与种子创建人，不改变阈值/启用状态/版本语义。
ALTER TABLE m2_anes.alert_rule DISABLE TRIGGER USER;

-- V15 默认种子阈值全部【待确认】：回填为 true（不是临床确认结论）。
UPDATE m2_anes.alert_rule
   SET pending_confirmation = true
 WHERE version = 1
   AND created_by IS NULL;

-- 系统用户 UUID：用于种子规则的 created_by/updated_by（契约 createdBy 必填）。
UPDATE m2_anes.alert_rule
   SET created_by = '00000000-0000-4000-8000-000000000000'::uuid,
       updated_by = '00000000-0000-4000-8000-000000000000'::uuid
 WHERE created_by IS NULL;

ALTER TABLE m2_anes.alert_rule ENABLE TRIGGER USER;

-- -----------------------------------------------------------------------------
-- 4. 抢救记录人员留痕（负责人必填 + 参与人员 ≥1；append-only 不变）
-- -----------------------------------------------------------------------------
ALTER TABLE m2_anes.resuscitation_record
    ADD COLUMN leader jsonb,
    ADD COLUMN rescuers jsonb;

-- 历史行回填（V15 未发布时通常为空；有 created_by 时以录入人作为负责人占位并标注待补录）。
-- 迁移期临时关闭 append-only 触发器（仅本迁移的回填 UPDATE；之后恢复拒绝 UPDATE/DELETE）。
ALTER TABLE m2_anes.resuscitation_record DISABLE TRIGGER USER;
UPDATE m2_anes.resuscitation_record
   SET leader = jsonb_build_object('userId', created_by::text, 'name', '历史记录（负责人待补录）')
 WHERE leader IS NULL
   AND created_by IS NOT NULL;

UPDATE m2_anes.resuscitation_record
   SET rescuers = jsonb_build_array(leader)
 WHERE rescuers IS NULL
   AND leader IS NOT NULL;
ALTER TABLE m2_anes.resuscitation_record ENABLE TRIGGER USER;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM m2_anes.resuscitation_record
         WHERE leader IS NULL
            OR rescuers IS NULL
            OR jsonb_typeof(leader) <> 'object'
            OR coalesce(leader ->> 'name', '') = ''
            OR jsonb_typeof(rescuers) <> 'array'
            OR jsonb_array_length(rescuers) = 0
    ) THEN
        RAISE EXCEPTION
            'V16 迁移自检失败：存在无法回填 leader/rescuers 的抢救记录，请先补录后重试'
            USING HINT = '抢救记录 append-only；请以新记录补录负责人/参与人员后由 DBA 处理';
    END IF;
END
$$;

ALTER TABLE m2_anes.resuscitation_record
    ALTER COLUMN leader SET NOT NULL,
    ALTER COLUMN rescuers SET NOT NULL,
    ADD CONSTRAINT ck_resuscitation_leader CHECK (
        jsonb_typeof(leader) = 'object' AND coalesce(leader ->> 'name', '') <> ''
    ),
    ADD CONSTRAINT ck_resuscitation_rescuers CHECK (
        jsonb_typeof(rescuers) = 'array' AND jsonb_array_length(rescuers) >= 1
    );

COMMENT ON COLUMN m2_anes.resuscitation_record.leader IS
    '抢救负责人 jsonb 对象 {userId?, name}（name 必填）；表内不存患者标识';
COMMENT ON COLUMN m2_anes.resuscitation_record.rescuers IS
    '抢救参与人员 jsonb 数组 [{userId?, name}]，至少 1 人；表内不存患者标识';

-- -----------------------------------------------------------------------------
-- 5. 迁移自检（fail fast）
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'm2_anes' AND table_name = 'anesthesia_record'
          AND column_name = 'room_id'
    ) THEN
        RAISE EXCEPTION 'V16 迁移自检失败：anesthesia_record.room_id 未创建';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'pk_alert_watermark'
          AND conrelid = 'm2_anes.alert_watermark'::regclass
    ) THEN
        RAISE EXCEPTION 'V16 迁移自检失败：alert_watermark 主键缺失';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'm2_anes' AND table_name = 'alert_rule'
          AND column_name = 'pending_confirmation'
    ) THEN
        RAISE EXCEPTION 'V16 迁移自检失败：alert_rule.pending_confirmation 未创建';
    END IF;
    IF (SELECT count(*) FROM m2_anes.alert_rule WHERE created_by IS NULL) <> 0 THEN
        RAISE EXCEPTION 'V16 迁移自检失败：仍有规则种子 created_by 为空';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_resuscitation_rescuers'
          AND conrelid = 'm2_anes.resuscitation_record'::regclass
    ) THEN
        RAISE EXCEPTION 'V16 迁移自检失败：抢救参与人员约束缺失';
    END IF;
END
$$;
