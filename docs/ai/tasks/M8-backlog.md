# M8 任务卡包（收口遗留与维护迭代）

> 建立于 2026-11-15（M8-M0-04 执行时）。M8 目标：收口 `docs/acceptance/FINAL-REPORT.md` §6.2
> 遗留项、维持契约/迁移/权限一致性，不新增临床口径。
> 使用方式：总控按依赖派子代理；核心域（权限/审计/契约/迁移/安全）独立 review、blocking 清零；
> `./scripts/mvn.sh -B -f apps/api/pom.xml clean verify` 与 `pnpm test:unit/build/lint/e2e` 全绿
> 才提交；一次最多 2 个任务且写域不重叠。

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M8-M0-04 | M0 公共支撑补全：字典 API、消息中心、文件管理 | platform.yaml（新域）、m0 模块、V27、前端 m0 | — | **Done（2026-11-15）** |
| M8-M4-01 | 耗材库存预警（阈值配置 + 低库存提醒 + 消息中心投递） | m4 模块、V28、material.yaml | M8-M0-04 | Ready |
| M8-M4-02 | 报表数据锁定/快照（运营口径确认后） | m4 模块、V28+、report.yaml | 院方口径签字 | Blocked（口径待确认） |
| M8-QA-01 | E2E-05 麻精双人核对页面流程 | `apps/web/e2e/**`、m4 页面 | M8-M0-04 | Ready |
| M8-M2-01 | 记录页 `vitalSigns` 直连设备域查询 | m2 模块、anesthesia.yaml | ADR-0016 Q-3 | Blocked（映射口径待临床确认） |
| M8-M2-02 | 抢救用药麻精判定（强制双人核对） | m2 模块、anesthesia.yaml | ADR-0018/0019 Q | Blocked（临床确认） |
| M8-DOC-01 | 契约 0.x deprecated 字段/端点清理 | 各契约 1.0.0（major） | ADR-0011、评审 | Ready（1.0.0 前） |

## M8-M0-04 交付记录（Done）

- **目标**：收口 FINAL-REPORT §6.2「M0 字典 API/消息中心/文件管理」，不扩散到 m1/m2/m4/m5 业务代码。
- **契约**：新增第 12 域 `packages/contracts/openapi/platform.yaml` 0.2.0（11 路径 / 12 operationId /
  18 schema；0.1.0 首版后评审修复增补「我的文件」、文件名服务端生成与 400/422 边界），
  baseline 快照 + `generated/typescript/platform.d.ts`；`scripts/gen-contracts.sh`、
  `contract-check.sh`、`packages/contracts/package.json`、`api-contracts/pom.xml` 接线到 12 域。
- **迁移**：`db/migration/V27__m0_dict_message_file.sql`（`sys_message`、`sys_file`、
  `idx_sys_dict_item_type_sort`、5 个三段式权限点与最小授予；中文注释 + 回滚说明）。
  注意：权限点 UUID 用 `b...86–b...8a`（`b...80–b...85` 已被 V21/V22 占用），插入 fail-fast。
- **后端**：`m0-api`（`DictService`/`MessageService`/`MessagePublisher`/`MessageTemplates`/
  `FileService`/`FileStorage`/`FileBusinessVisibilityPort` 与异常）、`m0-impl`（三个服务 +
  `LocalFileStorage` + `MinioFileStorage` 占位 + `FileProperties`）、`api-bootstrap`
  （`DictController`/`MessageController`/`FileController` + `FileBusinessVisibilityAdapter` +
  异常映射 + 文件配置）。
- **权限**：字典全院读、维护仅 `SYS_ADMIN`（权限点 + 服务层角色双校验）；消息仅本人（非本人 404）；
  文件按业务类型可见性 + **业务对象数据范围**（SCHEDULE/RECORD 调 m1/m2 DataScope，超范围 404；
  REPORT fail-closed）；审计 `DICT_*`/`MESSAGE_READ*`/`FILE_UPLOAD|DOWNLOAD`，文件 detail 不含
  fileName/businessId。
- **PHI**：消息模板注册表（服务端标题）、文件类型/大小白名单（≤20 MiB）、**不持久化客户端原始
  文件名**、`businessId` 只允许 UUID/大写业务号；禁止 DICOM/视频/身份证明/含患者标识表格；
  病毒扫描占位 **EXT-07**；本地 FS + compose 持久卷 `m0filedata`，MinIO 接口占位 **EXT-06**（Q-23）。
- **前端**：`/m0/dicts`（管理）、`/m0/messages`（未读角标/已读）、`/m0/files`（上传/下载示例页 +
  `FileUploadPanel` 复用入口）；路由/菜单/权限；Vitest 17 个新用例；E2E `m0-messages.spec.ts`。
- **测试证据**：`PlatformDictServiceTest`（14）、`PlatformMessageServiceTest`（10）、
  `PlatformFileServiceTest`（14）、`PlatformStorageTest`（3）、`FileBusinessVisibilityAdapterTest`（3）、
  `M0PlatformApiIntegrationTest`（4）、前端 6 个 spec（20+）、E2E 1 条；m0-impl platform 包
  分支覆盖 228/320≈71%、行 517/551≈94%。
- **文档**：`docs/modules/m0/README.md` §10、`docs/db/README.md` §4.22、`docs/api/README.md` §3.19、
  `docs/data-dictionary/core-entities.md` §3.17、`docs/adr/0024-字典消息文件口径.md`、
  FINAL-REPORT §6.2 勾销；评审 Q-22（首域 baseline SKIP 留痕）/Q-23（MinIO/EXT-06 口径）/
  Q-24（REPORT fail-closed）登记于 `docs/decisions.md`。
- **验收证据（2026-11-15）**：`mvn clean verify` BUILD SUCCESS；12 域契约 lint/breaking/漂移 PASS；
  `pnpm test:unit` 41 文件/200 用例、`pnpm build`、`pnpm lint`、`pnpm e2e` 46 条全绿；
  `compose-check.sh` PASS（36 项，EXT-08 运行时跳过）；`git diff --check` 通过。
- **遗留/边界**：MinIO（EXT-06）、病毒扫描（EXT-07）、文件保留期与归档策略、消息模板文案清单
  待院方/运维确认；Base64 上传 ≤20 MiB（流式上传随 MinIO 一起评估契约 0.2.0）。

## 后续任务要点

### M8-M4-01 耗材库存预警
- 目标：可配置低库存阈值（按耗材/科室）、低于阈值生成站内消息（复用 `MessagePublisher`，
  模板 `MATERIAL_LOW_STOCK`，参数 `count`/`deptName`，严禁患者标识），并记录审计。
- 验收：阈值边界、只发一次（去重键）、消息仅本人/角色可见、权限（`m4:material:read`）、
  库存不产生负值；V28 迁移可回滚。

### M8-M4-02 报表数据锁定/快照
- 前置：院方确认“报表冻结时点/重算口径/审计要求”（当前 Blocked，不得先写实现）。
- 方向：按周期生成快照表（只读），导出带口径版本与快照 ID；不修改既有 report-1.0.0 口径。

### M8-QA-01 E2E-05 麻精双人核对页面流程
- 目标：双人登录/切换、核对签字、拒绝与撤销分支；复用 `M4NarcoticApiIntegrationTest` 的
  合成数据；E2E 不连真实后端（API 拦截）。

### M8-M2-01 / M8-M2-02
- 均为临床口径 Blocked（ADR-0016 Q-3、ADR-0018/0019 Q），确认后按契约 minor 升级实现，
  不得用“占位值”冒充临床结论。

### M8-DOC-01 契约 1.0.0 清理
- 删除 0.x deprecated 字段/端点（ADR-0011），同步 baseline 与前端生成物；major 升级需双人评审
  与兼容说明；`platform.yaml` 一并评估是否引入 multipart/预签名下载（EXT-06）。
