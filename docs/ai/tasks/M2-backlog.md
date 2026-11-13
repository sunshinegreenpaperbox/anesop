# M2 任务卡包（MVP：排程 + 术前，目标 2027-01-29）

> 由 M1 收口会话生成。使用方式：总控按依赖每次选 1–3 个 Ready 任务，派子代理执行；
> 子代理简报必须包含：目标、验收（Given/When/Then）、写域、相关契约/章节、禁止事项。
> 契约冻结规则：Sprint 第 3 个工作日后契约冻结（阻断缺陷除外）；契约变更须同步 baseline/TS/Java DTO。
> 门禁：`./scripts/check.sh` 与 `scripts/ci-local.sh --fast` 全绿；核心域（契约/迁移/权限审计/安全）
> 必须独立 review 子代理、blocking 清零；一任务一提交。

## 依赖顺序

```
M2-M0-01 (data_scope 绑定) ─┐
M2-M0-02 (phone 写路径)   ─┼─→ M2-M1-01 申请接收 ─→ M2-M2-01 排程冲突 ─→ M2-M2-02 停改台/急诊
M2-M0-03 (E2E-01/04)      ─┘                         └─→ M2-M4-01 术前访视
                                                       M2-M5-01 知情同意/三方核查
                                                       M2-M6-01 排程分析
M2-M0-04 (ratchet/漏洞例外清理，可与任意任务并行)
```

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M2-M0-01 | 数据范围绑定表与解析 | `db/migration/V5*`、`apps/api/modules/m0/**`、契约 auth.yaml | M1 | Done（独立 review PASS，0 blocking；122 测试） |
| M2-M0-02 | 手机号加密与 hash 写路径 | `db/migration/V6*`、`apps/api/modules/m0/**`、契约 | M1 | Review（V6 + 契约 0.5.0 + 用户管理；待独立 review） |
| M2-M0-03 | E2E-04 越权 + 登录 E2E + 覆盖率 ratchet 清理 | `apps/web/e2e/**`、各 `src/test/**`、根 pom | M1 | Done（m0 96/87、gateway 91/77；ratchet 清零；9 E2E） |
| M2-M1-01 | 手术申请接收与校验 | `packages/contracts/openapi/{patient,scheduling}.yaml`、`apps/api/modules/m1/**`、`apps/web/src/modules/m1/**`、`db/migration/V7*` | M2-M0-01 | Review（blocking/建议已修：B-1/B-2/B-3 + S-1…S-9 + Q-1…Q-3；待复审） |
| M2-M2-01 | 排程工作台与冲突检测 | 同上（scheduling/m1） | M2-M1-01 | Review（独立评审 blocking B-1…B-4 与 S-1…S-9 已修，待复审） |
| M2-M2-02 | 停改台与急诊加台 | m1 契约/模块、通知模拟 | M2-M2-01 | Review（独立评审 B-1/B-2 + S-1…S-7 + Q-1…Q-3 已修；待复审） |
| M2-M4-01 | 术前访视与评估 | `anesthesia.yaml` 扩展、`apps/api/modules/m2/**`、`apps/web/src/modules/m2/**`、`db/migration/V10*` | M2-M1-01 | Review（契约 0.3.0 + V10 + m2 后端/前端 + 测试；待独立 review） |
| M2-M5-01 | 知情同意与三方核查 | anesthesia 契约（三方核查已定义）、m2 模块 | M2-M4-01 | Blocked |
| M2-M6-01 | 排程分析（利用率/准点/取消） | m1 读模型、M6 报表引擎或 m1 分析接口 | M2-M2-01 | Blocked |

## 任务卡要点（执行时按 BACKLOG 模板补全为 TASK-<id>.md）

### M2-M0-01 数据范围绑定表与解析
- 目标：新增 `sys_org`/`sys_user_org`（科室）、`sys_user_room`（术间）与 `sys_role_scope` 或复用 data_scope 列，
  `DataScopeResolver` 实现 ALL/SITE/DEPT/ROOM/SELF，空范围 fail-closed；契约 DataScope/CurrentUser 同步。
- 验收：SYS_ADMIN→ALL；DEPT 用户只能看本科室数据（用测试接口或查询条件断言）；未绑定→拒绝；
  越权 403 + 审计；迁移 expand-contract + 回滚说明；契约 baseline/TS/Java 同步；独立 review。
- 写域：`db/migration/V5__m0_data_scope.sql`、`apps/api/modules/m0/**`、`packages/contracts/openapi/auth.yaml(+baseline)`、`docs/db/README.md`。

### M2-M0-02 手机号加密与 hash 写路径
- 目标：`PhoneHashing`（HMAC-SHA256 + 配置密钥，缺失拒绝启动/降级为禁用）、AES-GCM 加解密 TypeHandler、
  用户 CRUD 契约（扩展 auth.yaml 或新 m0.yaml 的用户管理端点）、写入/查询/脱敏返回。
- 验收：明文不入库、`phone_hash` 精确查询命中、密钥轮换说明、日志/响应无明文、越权 403；单测 + 集成。
- 写域：`db/migration/V6__m0_phone_crypto.sql`、`apps/api/modules/m0/**`、契约、`docs/modules/m0/README.md`。
- 落地说明（Review）：迁移文件为 `db/migration/V6__m0_user_phone_crypto.sql`（`phone_key_id`/`phone_updated_at`
  + `ck_sys_user_phone_crypto` 四列一致性约束 + `phone_hash` 部分索引 + `m0:user:read|write` 仅授 SYS_ADMIN，
  V2 历史 `user:read/write` 保留）；`PhoneCrypto`（AES-256-GCM，`ANESOP_FIELD_KEY`）、`PhoneHasher`
  （HMAC-SHA-256，`ANESOP_PHONE_PEPPER`）、`FieldCryptoKeyProvider`（prod 缺失/非法拒绝启动，dev/test 自动生成并 WARN）；
  `UserAdminService` + `GET/POST /v1/auth/users`、`GET/PUT /v1/auth/users/{userId}`、
  `/password-reset`（契约 0.5.0，baseline/TS/Java 同步）；DTO 只返回 `phoneMasked`；
  USER_CREATE/USER_UPDATE/PASSWORD_RESET 审计 detail 不含手机号。测试：24 单测 + 7 集成 + 迁移与启动守卫用例。

### M2-M0-03 E2E 与覆盖率清理
- 目标：E2E-04 权限越权（登录→无权限访问→403 页面）、登录提交 E2E、E2E-01 排程冲突（依赖 M2-M2-01，
  可拆后置）；清理 m0-impl / gateway 覆盖率 ratchet（补单测至核心域 80/70）；升级 vitest 工具链清除
  devDeps 漏洞例外（glob/tinypool）；删除到期例外并更新 docs/testing/README、audit-exceptions。
- 验收：check.sh + ci-local 全绿；ratchet 与到期临时标记全部删除且覆盖率达标；E2E 稳定（3 次重跑）。

### M2-M1-01 手术申请接收与校验
- 目标：按 patient/scheduling 契约实现申请单创建/查询/列表；HIS 适配器（模拟，EXT-01）：接收 HL7/REST
  模拟申请→映射→幂等入库；校验规则（必填、术式、术前检查、重复申请）；列表/详情页（Element Plus）。
- 验收：申请单 CRUD + 分页 + 权限（m1:application:read/write）；重复申请幂等/冲突 409；模拟 HIS 推送
  →入库→页面可见；越权 403 + 审计；单元/集成/契约一致性；E2E 主流程；迁移 V7 + 回滚说明；独立 review。

- 落地说明（Review 修复后）：契约 patient/scheduling 0.3.0 保持 additive；患者摘要 `idCardMasked/phoneMasked/
  inpatientNo` 以 `deprecated: true` 兼容占位恢复（实现返回空字符串，不返回真实值），`patientNo` 弃用并统一
  `hisPatientNo`，`patientId` 示例改 UUID；新增 `POST /v1/patients/search`，旧 `GET /v1/patients` 标 deprecated；
  历史 `/v1/surgery-applications*` 保留 deprecated（计划 1.0.0 下线），导入 422 移除、保留 400/403/409。
  V7 `patient_snapshot`（住院号 masked + HMAC 哈希、无明文）/`surgery_application`（幂等键）/
  `application_status_history`（reason_code/reason 分列 + UPDATE/DELETE/TRUNCATE 触发器）+ 权限
  `m1:application:read|write`、`m1:patient:read`；住院号 pepper fail-closed（仅 dev/test 显式自动生成）；
  患者 upsert 改单条 `INSERT ... ON CONFLICT DO UPDATE ... RETURNING id`（并发导入不重复）；birthDate 缺失拒绝
  导入/创建；Mock 适配器 count 严格 1–5；前端列表/详情 + 权限按钮 + POST 患者检索；Nginx `/api/` 无 query
  日志格式。测试：m1-impl 单测 44、后端集成 10（并发导入/append-only/权限/搜索/边界）、M1 E2E 4、V7 迁移结构
  断言、契约示例一致性；contract-check 全绿。

### M2-M2-01 排程工作台与冲突检测
- 目标：术间/台次/日期排程；规则引擎（术间类型/时长/优先级/医师资质/设备）；冲突检测（时间重叠、
  资质不符、设备冲突）；拖拽调整；甘特/看板；冲突 409 + 建议。
- 验收：E2E-01 排程冲突（制造冲突→被拦→解决）；规则可配置；并发排程不超卖（唯一约束/乐观锁）；
  单位/集成测试；性能（1000 条 <2s 本地基准）。

- 复审修复（独立 review blocking/suggestion）：
  - **B-1** 创建/调整/冲突预检在术间范围外再校验申请/患者可见性（DEPT/SITE 跨科室→404 不泄露
    存在性；ROOM 范围按绑定术间 dept/site 推导的文档例外），403/404 响应体断言不含
    `patientName/hisPatientNo`；
  - **B-2** `GET /rooms` 实现 `siteId`/`status` 过滤，非法参数 400（集成用例覆盖参数生效与 400）；
  - **B-3** deprecated `POST /v1/scheduling/conflicts/check` 映射同一 `checkConflicts`（200 且
    响应与新端点一致），不删除（ADR-0011）；
  - **B-4** 跨术间术者/患者并发加 `pg_advisory_xact_lock`（key 字典序，先人员/患者后术间行锁），
    `CyclicBarrier` 双线程用例验证同术者/同患者跨术间恰一成功；README §9 记录死锁规避；
  - **S-1** ADR-0011 增补记录 enum→x-extensible-enum 属非严格 additive（TS 变 `string`），
    强类型诉求记 Q；**S-2** 新增 `APPLICATION_ALREADY_SCHEDULED/VERSION_CONFLICT/STATE_CONFLICT`
    替换误用；**S-3** `schedule.priority` 落库回显，`staffIds/equipmentTags` 契约标
    `x-deferred: true`；**S-4** V8 结构断言（部分唯一索引/时间窗/优先级/权限/5 术间）；
    **S-5** `CyclicBarrier` + 显式 READ_COMMITTED；**S-6** 1000 条查询基准 <2s 与前端分支单测；
    **S-7** SYS_SURGEON 授 `m1:schedule:read`；**S-8** 409 从 `conflicts[]` 解析（保留
    `conflictingScheduleId`）、仅 PLANNED/PUBLISHED 显示操作、本地时区日期；**S-9** reasonCode
    白名单正则、时间窗非法统一 400。
- 落地说明（Review）：契约 scheduling **0.4.0**（additive：`roomType`/`requiredRoomType`、
  `PUT /v1/scheduling/schedules/{id}`、`POST /v1/scheduling/schedules/conflicts`、
  `Error.conflicts[]`；旧 `conflicts/check` 标 deprecated）；`conflictType` 改 `x-extensible-enum`
  以允许新增规则类型而通过 `oasdiff --fail-on WARN`。V8 `operating_room`（room_no 唯一/类型
  GENERAL/HYBRID/INTERVENTION/状态/科室/审计+version）与 `schedule`（FK/时段检查/索引/申请活动台次
  部分唯一索引），`surgery_application.required_room_type` 可空列；权限 `m1:schedule:read|write`
  仅授 SYS_ADMIN/SYS_OR_MANAGER；5 个 SYNTHETIC 术间（OR-02 HYBRID、OR-03 INTERVENTION，
  OR-05 属手术室用于 DEPT 负例）。规则引擎 `SchedulingRule` + 注册表：ROOM_OVERLAP/
  STAFF_CONFLICT/PATIENT_CONFLICT/ROOM_TYPE_MISMATCH/TIME_WINDOW_INVALID；创建/调整事务内
  `SELECT ... FOR UPDATE` 锁术间行后复检，冲突 409 + conflicts[]；DataScope DEPT/ROOM；
  `SCHEDULE_CREATE/UPDATE/CANCEL` 审计。前端 `ScheduleBoardView` + API client + 5 Vitest +
  E2E-01（409→提示→改术间成功）。测试：m1-impl 单测 94（新增规则 15 + 服务 34），m1-impl
  覆盖率 94.79%/74.11%；集成 `SchedulingApiIntegrationTest` 8 用例（含两线程并发同室同窗恰一成功）。
  范围外必要改动：`m0-impl` 的 `M1MigrationTest` 表清单断言加入 V8 两表（仅测试夹具，无 m0 业务代码变更）。

### M2-M2-02 停改台与急诊加台
- 目标：停台/改台（原因码、审批、变更留痕、通知）；急诊加台优先于择期，可抢占冲突择期台次；
  变更历史查询；通知端口（模拟）与 outbox。
- 验收：停台状态机（PLANNED/PUBLISHED 直停；IN_PROGRESS 需 approve，否则 403）；原因码白名单
  （非法 400）；急诊加台审批（无 approve 403）与抢占（原择期 CANCELLED + 审计/通知）；未指定
  抢占遇冲突 409 + conflicts[]；改台写 MODIFY 变更；changes 查询按数据范围；并发仍不超排；
  outbox 无 PHI；契约 baseline/TS/Java 同步；mvn verify + 前端四门禁全绿；独立 review。

- 落地说明（Review）：契约 scheduling **0.5.0**（additive）：新增
  `POST /v1/scheduling/schedules/{scheduleId}/stop`（200 + CANCELLED）、
  `GET /v1/scheduling/schedules/{scheduleId}/changes`、急诊 201 + `approval`、
  请求 `preemptScheduleId/expectedEnd/sequenceNo`、改台可选 `reasonCode`；旧 `.../cancel` 与
  急诊 200 后经复审移除（Q-3）。V9 `m1_sched.schedule_change_request`（STOP/MODIFY/EMERGENCY，
  审批一致性/原因码 CHECK，preempt 自引用 FK）与 `m1_sched.notification_outbox`
  （PENDING/SENT/FAILED + 退避字段 + `ck_notification_outbox_no_phi` 禁 PHI 键）+ 权限
  `m1:schedule:approve`（SYS_ADMIN/SYS_OR_MANAGER）。后端：`SchedulingServiceImpl.stop/
  addEmergency/listChanges`，事务内术间行锁 + advisory 锁，急诊抢占仅消除 ROOM_OVERLAP，
  写变更/审计（`SCHEDULE_STOP/PREEMPT/EMERGENCY_CREATE`）/outbox；`NotificationPort`
  （SIMULATED）+ `SimulatedNotificationPort` + `NotificationDispatcher`（每次 drain 1 条，
  SKIP LOCKED，3 次失败 FAILED）；`@Scheduled` 轮询默认开、测试 profile 关。前端：
  `ScheduleBoardView` 停台/改台原因码对话框、急诊加台（冲突内抢占 CTA）、变更历史抽屉；
  E2E 停台 + 急诊抢占（API 拦截）。测试：m1-impl 新增 24 个单测（服务 15：停台状态机/原因码/403、
  改台、急诊审批/抢占/409、changes；通知派发 6 + 模拟端口 3），api-bootstrap 新增 8 用例
  （含并发急诊不超排、outbox PHI 约束），M1MigrationTest 增 V9 结构断言；实测 m1-impl 行/分支 **93.67%/71.25%**（≥80/70）。范围外必要改动：`apps/api/modules/m0/impl/.../M1MigrationTest.java`
  的表清单与 V9 断言（仅测试夹具，无 m0 业务代码变更）。

- 复审修复（2026-11-13，独立评审 B-1/B-2 + S-1…S-7 + Q-1…Q-3）：
  - **B-1** 急诊抢占目标新增 `ScheduleMapper.selectByIdForUpdate`（`SELECT ... FOR UPDATE`），
    锁内复检 status（PLANNED/PUBLISHED）、priority（ELECTIVE）、roomId 与时间窗；不满足 409
    `PREEMPT_CONFLICT`。留痕/审计/outbox 改用复检后实体，锁顺序 advisory → 术间行 → 被抢占行。
    新增 CyclicBarrier 用例：并发“改台移出 + 急诊抢占”（恰一成功、不误停）、并发重复 stop
    （变更/审计/outbox 各 1 条）。
  - **B-2** V9 为 `schedule_change_request` 加完全 append-only 触发器（拒绝 UPDATE/DELETE/
    TRUNCATE，SQLSTATE 55000）；审批结果同事务直接写 APPROVED 行；迁移测试断言三种改写均被拒。
  - **S-1** 旧 `/cancel` 复用 `writeStopTrail`（change_request + outbox + 审计）。
  - **S-2** 抢占 STOP 说明改为可配置中性文案 `anesop.m1.schedule.preempt-reason`。
  - **S-3** Controller `addEmergency` 改为 `hasAuthority(PERM_m1:schedule:approve)`，服务层双保险。
  - **S-4** `ScheduleChange/EmergencyApproval` 的 `approvedBy/approvedAt` 标 `nullable`（3.1 语法），
    baseline/TS 同步。
  - **S-5** 前端单测真实触发术中停台 403（按钮对 write 用户可见、服务端双保险拒绝）；E2E 新增
    无审批术中停台 403 用例。
  - **S-6** 抢占按钮 Element Plus `ElMessageBox.confirm` 二次确认，文案含被抢占台次信息。
  - **S-7** 急诊缺省台次序号按术间计数；`GET .../changes` 增加 `page/size/meta`（additive）；
    outbox 增加值级 PHI 约束 `ck_notification_outbox_no_phi_value`（15 位以上连续数字）；
    新增多实例 SKIP LOCKED 并发派发用例（两条各处理一次、attempts=1）。
  - **Q-1** PUT 改台 `reasonCode` 契约必填（缺失 400），前端不预填、必须选择；baseline/TS/
    服务/单测/集成同步。
  - **Q-2** `docs/modules/m1/README.md` §4.3 与 ADR-0012 登记：上线前需独立审批角色/双人双控（M6）。
  - **Q-3** 移除急诊端点不可达的 200 响应，仅保留 201；文档同步。
  - 契约兼容说明：Q-1/Q-3 相对 HEAD 的 0.4.0 baseline 属未发布窗口收紧，登记 ADR-0012；
    `contract-check.sh` HEAD baseline 需在提交评审通过的 0.5.0 baseline 后转绿（不绕过门禁）。

### M2-M4-01 术前访视与评估
- 目标：访视单（病史/气道/ASA）、评分（RCRI/Apfel/STOP-BANG/Mallampati）、麻醉计划、签署状态；
  评分项与阈值口径待临床确认（标【待确认】，不硬编码）。
- 验收：访视单创建/更新/查询；评分计算可测（正常/边界/缺项）；麻醉计划与术式关联；越权 403；
  E2E 主流程；契约扩展 + baseline/TS/Java 同步；临床规则需人工逐行（AGENTS 高风险）。

- 落地说明（Review）：契约 anesthesia **0.3.0**（additive）：新增
  `GET/POST /v1/anesthesia/preop-assessments`、`GET/PUT /{assessmentId}`、
  `POST /{assessmentId}/finalize`（operationId list/create/get/update/finalizePreopAssessment），
  `PreopAssessment`/`PreopScoreInput`/`PreopScores`/`PreopAirwayAssessment`/`PreopPlan`/
  `PreopApplicationSummary` 等 schema；旧 `preop-visits` 骨架保留（ADR-0011 弃用窗口）；
  baseline/TS/Java 同步，contract-check（lint/baseline/HEAD baseline/drift）全 PASS。
  V10 `m2_anes.preop_assessment`（申请物理 FK、患者快照/气道/评分输入/评分结果 jsonb、
  四项分数与风险列、DRAFT/FINALIZED、最终化人/时间、审计/version/deleted；同一申请最多一份
  草稿的部分唯一索引；中文注释 + 回滚说明）+ 权限 `m2:preop:read|write`
  （SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。后端：`m2-api` 服务接口与异常、
  `m2-impl` `PreopScoreCalculator`（RCRI 6/Apfel 4/STOP-BANG 8/DASI 12 项 0–58.2，公开文献
  Javadoc，阈值【待确认】）+ `PreopAssessmentServiceImpl`（事务、M1 申请可见性复用、乐观锁、
  最终化锁定、PREOP_CREATE/UPDATE/FINALIZE 审计无 PHI）、bootstrap 控制器与 400/409/422 映射。
  前端：`/m2/preop/:applicationId` 术前访视页（病史/气道/ASA/评分勾选/计划、实时预览、二次确认
  最终化、403/404 权限态）+ 菜单入口 `/m2/preop`（`m2:preop:read`）+ API client。
  测试：m2-impl 52（评分表驱动 28 + 服务 24，覆盖率 LINE 94.96%/BRANCH 74.67% ≥80/70）、
  api-bootstrap 集成 4（主流程/400-422-409/403-404/V10 结构）、Vitest 6、E2E 2；
  `mvn verify`、契约门禁、`pnpm test:unit`/`build`/`lint`/`e2e` 全绿。
  已知限制：SYS_ANESTHESIOLOGIST 种子为 SELF 数据范围，HIS 申请需 DEPT/ALL 或 M3 任务分派范围
  （模块 README §9 登记）；评分阈值配置表【待确认】。

### M2-M5-01 知情同意与三方核查
- 目标：模板与版本、签署状态（模拟 CA，EXT-05）、Time-out 记录、三方核查（已定义契约）与锁单前置联动。
- 验收：签署流程状态机、模板版本不可变、核查完成才能锁单（409）、审计留痕、CA 占位接口 + 模拟实现。

### M2-M6-01 排程分析
- 目标：术间利用率、准点开台率、取消率；口径可配置（D-21 报表口径）；多维筛选/导出 CSV（脱敏）。
- 验收：统计结果与手算一致（固定合成数据集）；性能 <10s；导出脱敏；指标口径文档化。

## M1 前欠账清单（可并行清理）

1. 覆盖率 ratchet（到期 2026-12-18，ADR-0008）。
2. devDependencies 漏洞例外（glob/tinypool，到期 2026-12-18）。
3. data_scope 绑定表（M2-M0-01）。
4. phone 写路径（M2-M0-02）——已完成，待 review。
5. E2E-01/04 与登录提交 E2E（M2-M0-03 部分）。
6. springdoc 全量导出 vs baseline drift（M0 域）。
7. nginx 非 root 镜像（M6 前，EXT-08 解除后实测）。

## 执行注意

- 契约变更：改 yaml → 更新 `baseline/`（人工评审后复制）→ `bash scripts/gen-contracts.sh` → `bash scripts/contract-check.sh` 全绿。
- 迁移：一任务一个 V 版本号（当前已用 V1–V4 + V100；M2 从 V5 起），expand-contract，不可改已合入脚本。
- 核心域（M2 记录、M0 权限审计、契约、迁移、安全）必须独立 review；临床规则人工逐行。
- 测试只用 SYNTHETIC 数据；日志/审计无 PHI；患者数据不出院。
- 每任务完成后更新 `PROJECT_STATE.md`、`docs/acceptance/STATUS.md`、必要时 ADR 与交接单。
