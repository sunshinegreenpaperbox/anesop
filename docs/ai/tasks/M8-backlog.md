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
| M8-M4-01 | 耗材库存预警（阈值配置 + 低库存提醒） | m4 模块、V28、material.yaml | M8-M0-04 | **Done（2026-11-15，并入 M8-M4-02 执行；页面预警，不投递消息中心）** |
| M8-M4-02 | 报表数据锁定/快照（锁定后查询/导出口径） | m4 模块、V28、report.yaml | 院方口径签字 | **Done（2026-11-15）** |
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

### M8-M4-01 耗材库存预警（Done，并入 M8-M4-02 执行）
- 交付：`material.yaml` 0.2.0（`GET /v1/materials/stock-alerts`、`PUT /v1/materials/{id}/low-stock-threshold`、
  `lowStockThreshold`/`MaterialStockAlert(StockAlertType)`/`LowStockThresholdRequest`）、V28
  （`m4_material.material.low_stock_threshold` numeric(12,2) 可空）、`MaterialAlertServiceImpl`
  + `MaterialMapper.updateLowStockThreshold` + `MaterialStockMapper.selectStockAlerts`（SQL `HAVING`
  先过滤再 LIMIT）、`MaterialController`/`MaterialStockController` 端点、审计
  `MATERIAL_THRESHOLD_UPDATE`、DataScope ALL/SITE/DEPT（SELF/ROOM 403）、前端库存预警卡与类型筛选。
- 口径：低库存=可用量**严格低于**阈值；零库存=合计 0；临期=效期 ≤ 今天+90 天（含已过期）；
  建议补货量 `max(0, 阈值−可用量)`，阈值缺失为 null。阈值来源、按科室粒度、临期天数【待确认】
  （m4 README §32 Q-8）。
- 边界：**未**做 MessagePublisher 站内消息投递（原任务卡设想的 `MATERIAL_LOW_STOCK` 模板）。
  原因：消息中心去重键/接收人规则（科室库管 vs 耗材管理员）与提醒频率属院方口径；本期以
  页面预警 + 可查询端点收口，投递留待口径确认后另开任务（不臆造接收人/频率）。
- 验收：阈值边界/零库存/临期/DataScope/403/审计 单测 16 + 集成 1；「M4 耗材库存预警边界与
  DataScope」证据见 `M4MaterialApiIntegrationTest#stockAlertsCoverThresholdZeroStockNearExpiryAndDataScope`。

### M8-M4-02 报表数据锁定/快照（Done）
- 交付：`report.yaml` 0.2.0（`POST /v1/reports/locks`、`GET /v1/reports/locks`、
  `GET /v1/reports/locks/{lockId}`、`ReportLock/ReportLockList/ReportLockRequest`、
  `WorkloadReport/MultiReport` 增 `source/lockId/lockedAt`、导出响应头 `X-Report-Source`）、
  V28 `m4_report.report_snapshot`（append-only、唯一 `(report, period, params_hash)`、params/payload
  jsonb + sha256）、`m4:report:lock`（SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER）、
  `ReportSnapshotCodec`/`ReportSnapshotEntity`/`ReportSnapshotMapper`、`ReportServiceImpl` 实现
  `ReportLockService`（锁定 + 列表/详情 + 查询/导出默认读快照）、前端锁定按钮与快照标识。
- 语义：期间 `YYYY-MM` 或 `YYYY-MM-DD~YYYY-MM-DD`（也可 from+to，二选一）；参数指纹含数据范围
  指纹（不同范围不共用快照），分页不参与；重复锁定 409；快照不可覆盖/删除（55000）；锁定/导出
  审计 `REPORT_LOCK`/`REPORT_EXPORT(source)`。快照保留期与解锁/重出数【待确认】（m4 README §41 Q-9）。
- 验收：`ReportLockServiceImplTest` 18 例 + `M4ReportApiIntegrationTest#reportLockFreezesSnapshotAndExportReadsSnapshot`
  （锁定后源数据变化仍为 3 台次/215 分钟、导出 `X-Report-Source=SNAPSHOT`、重复 409、append-only
  55000、审计、无 lock 权限 403、V28 权限与 3 条授权）+ 前端 Vitest 1 + E2E 1。

### M8-QA-01 E2E-05 麻精双人核对页面流程
- 目标：双人登录/切换、核对签字、拒绝与撤销分支；复用 `M4NarcoticApiIntegrationTest` 的
  合成数据；E2E 不连真实后端（API 拦截）。

### M8-M2-01 / M8-M2-02
- 均为临床口径 Blocked（ADR-0016 Q-3、ADR-0018/0019 Q），确认后按契约 minor 升级实现，
  不得用“占位值”冒充临床结论。

### M8-DOC-01 契约 1.0.0 清理
- 删除 0.x deprecated 字段/端点（ADR-0011），同步 baseline 与前端生成物；major 升级需双人评审
  与兼容说明；`platform.yaml` 一并评估是否引入 multipart/预签名下载（EXT-06）。
