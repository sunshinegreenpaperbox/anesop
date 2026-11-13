# M3 任务卡包（术中/术后闭环，目标 2027-03-12）

> M2 已完成（2026-11-14）。M3 范围：麻醉记录单与时间轴、设备采集接入、术中预警与抢救、
> 复苏与转出、术后随访与镇痛、锁单/修订/CA 签名。使用方式同 M2：总控按依赖每次选 1–3 个
> Ready 任务派子代理；核心域（M2 记录、M0 权限审计、契约、迁移、安全）必须独立 review、
> blocking 清零；门禁 `./scripts/check.sh` 全绿才提交。

## 依赖顺序

```
M3-M2-01 记录单/时间轴/用药/输液/出入量 ─→ M3-M2-02 预警与抢救 ─→ M3-M2-03 锁单/修订/CA
            │                                   └─→ M3-M10-01 复苏/转出 ─→ M3-M11-01 随访/镇痛
M3-M8-01 采集接入平台端（质量标记/幂等接收） ─┘（为时间轴提供体征与质量标记）
M3-QA-01 临床记录专项测试（时间轴/断网/并发/签名锁定）与 E2E-02/03
```

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M3-M2-01 | 麻醉记录单、时间轴、用药/输液/出入量 | `anesthesia.yaml`、`apps/api/modules/m2/**`、`apps/web/src/modules/m2/**`、`db/migration/V13*` | M2-M4-01/M2-M5-01 | Review（2026-11-14，独立评审 blocking B-1/B-2/S-1…S-5 已修复，待复审 + 全量 verify） |
| M3-M8-01 | 采集接入平台端（网关→平台体征、质量标记、幂等） | `services/device-gateway/**`、`db/migration/V14*`、`apps/api/modules/m2/**`、契约 device.yaml/anesthesia | S0-09 | Review（2026-11-14，独立评审 blocking B-1/S-1…S-6 已修复，待复审 + 全量 verify） |
| M3-M2-02 | 术中预警规则与抢救记录 | m2 契约/模块、前端 | M3-M2-01 | Blocked |
| M3-M2-03 | 锁单/修订/CA 签名（签名模拟） | m2 契约/模块、前端 | M3-M2-01 | Blocked |
| M3-M10-01 | 复苏记录与转出（Aldrete、交接） | m2 契约/模块、前端 | M3-M2-01 | Blocked |
| M3-M11-01 | 术后随访与镇痛（24/48h、PCA、并发症） | m2 契约/模块、前端 | M3-M10-01 | Blocked |
| M3-QA-01 | 临床记录专项测试 + E2E-02/03 | `apps/web/e2e/**`、各 `src/test/**` | M3-M2-03、M3-M8-01 | Blocked |

## 任务卡要点

### M3-M2-01 麻醉记录单与时间轴
- 目标：记录单创建（关联排程台次/申请/患者）、时间轴事件（麻醉开始/诱导/插管/切皮/关键事件/
  拔管/结束）、用药（麻精双人核对字段）、输液/输血、出入量、生命体征展示（来自采集）、
  打印/PDF 占位；契约 anesthesia 0.5.0 additive；V13 `m2_anes.anesthesia_record`、
  `record_event`（append-only + version）、`drug_administration`、`fluid_record`、
  `intake_output`；权限 m2:record:read/write；并发/锁单前置（未锁单可改，记录版本）。
- 验收：记录单与台次关联唯一、事件时间轴有序且 append-only、用药（麻精）双人核对必填、
  并发修改乐观锁 409、越权 403、审计、E2E-03 前置。
- 写域：契约 anesthesia + baseline/TS、V13、`apps/api/modules/m2/**`、bootstrap、web m2、docs。

### M3-M8-01 采集接入平台端
- 目标：网关标准化体征数据经平台接收（REST/批量或直连 DB 的防绕过校验），`m2_anes.vital_sign`
  /质量标记（`quality_flags`）、幂等（gateway_id+seq+measured_at）、与记录单时间轴关联；
  设备状态/质量统计；契约 device.yaml（网关→平台）additive；权限/内部认证（网关 token）。
- 验收：断网补传幂等（复用网关测试）、质量标记正确、单位标准化、无 PHI、审计/指标；
  E2E-02（断网补传）与网关联动。
- 写域：`services/device-gateway/**`、V14、`apps/api/modules/m2/**`、`packages/contracts/openapi/device.yaml`、docs。

### M3-M2-02 术中预警与抢救记录
- 规则可配置（阈值/持续时间/规则版本）、告警留痕（触发/确认/忽略）、抢救记录（人员/用药/事件）、
  生命体征触发（来自采集）；临床阈值【待确认】、规则版本化。
### M3-M2-03 锁单/修订/CA 签名
- 锁单前置：三方核查完成 + 记录完整性；锁单后不可改，只能新修订版本（原值/修改人/时间/原因，
  可比对）；CA 签名占位（EXT-05，自签 hash）；E2E-03。
### M3-M10-01 复苏记录与转出
- PACU 记录、Aldrete 评分、转出条件、交接记录（SBAR）、与排程/病房衔接。
### M3-M11-01 术后随访与镇痛
- 24/48h 随访单、镇痛方式（PCA 参数）、并发症/不良事件、患者反馈（脱敏）。
### M3-QA-01 临床记录专项测试
- 时间轴一致性、断网采集补传（E2E-02）、并发修改、签名锁定（E2E-03）为发布准入项；
  **E2E-02（断网补传）由 M3-QA-01 承接**（M3-M8-01 只交付平台 ingest 与网关适配器单测/集成，
  Playwright 端到端补传场景归 QA 任务）；E2E-04 越权已在 M2 覆盖，E2E-05 麻精双人核对随 M4/M5。

## 执行注意

- 迁移从 V13 起（V1–V12 已用，禁止修改）；expand-contract + 回滚说明。
- 契约 additive；HEAD baseline 检查必须 PASS（`contract-check` 已自动化）。
- 临床规则/阈值由临床顾问确认，未确认标【待确认】并在 ADR 记录。
- 记录不可变：锁单后只能新增修订版本，保留原值/修改人/时间/原因；数据库层 append-only 约束。
- 测试只用 SYNTHETIC；日志/审计无 PHI；E2E 与专项用例为发布准入。

## M3 期待办清单（跨任务，登记不实现）

| # | 事项 | 归属 / 触发条件 |
|---|---|---|
| T-1 | 排程/申请页跳转「麻醉记录」入口 | `apps/web/src/modules/m1` 视图属 M1 写域；需 M1 视图任务或 M3-M2-01 复审后追加写域，当前只支持直接访问 `/m2/records/:scheduleId` |
| T-2 | 共享令牌 token→gateway 绑定 / 单网关密钥 | 生产前（M3-M8-01 S-5 登记；见 `docs/gateway/README.md` §11.2、`docs/modules/m2/README.md` §12.1） |
| T-3 | 麻精药品服务端判定（`narcotic` 不再由客户端声明） | 药品字典 I-07 落地后由 M5 承接（Q-8） |
| T-4 | 临床角色按本台次读取体征（`vitalSigns` 映射口径） | M3-QA/M4 权限细化（Q-9） |
| T-5 | E2E-02 断网补传端到端用例 | M3-QA-01 |
