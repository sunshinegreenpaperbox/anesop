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
| M2-M1-01 | 手术申请接收与校验 | `packages/contracts/openapi/{patient,scheduling}.yaml`、`apps/api/modules/m1/**`、`apps/web/src/modules/m1/**`、`db/migration/V7*` | M2-M0-01 | Review（契约 0.3.0 + V7 + m1 后端/前端；待独立 review） |
| M2-M2-01 | 排程工作台与冲突检测 | 同上（scheduling/m1） | M2-M1-01 | Blocked |
| M2-M2-02 | 停改台与急诊加台 | m1 契约/模块、通知模拟 | M2-M2-01 | Blocked |
| M2-M4-01 | 术前访视与评估 | `anesthesia.yaml` 扩展、`apps/api/modules/m2/**`、`apps/web/src/modules/m2/**`、`db/migration/V8*` | M2-M1-01 | Blocked |
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

- 落地说明（Review）：契约 patient/scheduling 0.3.0（`/v1/scheduling/applications` 创建/列表/详情/模拟导入/取消；
  患者摘要 `hisPatientNo` 过滤、移除证件号/手机号；历史 `/v1/surgery-applications*` 标 deprecated，baseline/TS 同步）；
  V7 `m1_sched.patient_snapshot`（住院号 masked + HMAC 哈希、无明文）/`surgery_application`（his_application_no 幂等）/
  `application_status_history`（append-only）+ `m1:application:read|write`；`m1-api` 端口/服务、`m1-impl`
  `MockHisApplicationAdapter`（fixed seed，simulated=true）与导入 `ON CONFLICT DO NOTHING`、状态机 409、
  取消历史 + 审计；`api-bootstrap` 控制器 + 404/409/422 映射 + DataScope（ALL/DEPT/SELF）；前端列表/详情 +
  权限按钮 + Vitest；测试：m1-impl 单测覆盖率 93%/70.7%，后端集成 7 用例（导入幂等/脱敏/409/404/400/403/DataScope），
  contract-check 全绿。

### M2-M2-01 排程工作台与冲突检测
- 目标：术间/台次/日期排程；规则引擎（术间类型/时长/优先级/医师资质/设备）；冲突检测（时间重叠、
  资质不符、设备冲突）；拖拽调整；甘特/看板；冲突 409 + 建议。
- 验收：E2E-01 排程冲突（制造冲突→被拦→解决）；规则可配置；并发排程不超卖（唯一约束/乐观锁）；
  单位/集成测试；性能（1000 条 <2s 本地基准）。

### M2-M4-01 术前访视与评估
- 目标：访视单（病史/气道/ASA）、评分（RCRI/Apfel/STOP-BANG/Mallampati）、麻醉计划、签署状态；
  评分项与阈值口径待临床确认（标【待确认】，不硬编码）。
- 验收：访视单创建/更新/查询；评分计算可测（正常/边界/缺项）；麻醉计划与术式关联；越权 403；
  E2E 主流程；契约扩展 + baseline/TS/Java 同步；临床规则需人工逐行（AGENTS 高风险）。

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
