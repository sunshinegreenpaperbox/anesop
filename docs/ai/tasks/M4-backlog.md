# M4 任务卡包（管理域，目标 2027-04-23）

> M3 已完成（2026-11-14，7/7）。M4 范围：人员档案/分级授权/排班/培训（E-M13）、
> 耗材主数据/出入库/UDI/高值追溯/SPD（E-M14）、药品目录/麻精五专/基数/日结班结/药房
> 适配器（E-M15）、工作量统计/多维报表/导出（E-M16）、质控指标/不良事件 RCA/病历质量
> 抽查/上报导出（E-M17）。使用方式同 M2/M3：总控按依赖派子代理；核心域（权限/审计/
> 契约/迁移/安全/临床）独立 review、blocking 清零；`./scripts/check.sh` 全绿才提交。

## 依赖顺序

```
M4-M15-01 药品目录与麻精五专（E-M15） ─┬─→ M4-M14-01 耗材/UDI/SPD（E-M14）
（同时收口 M3 T-4：麻精判定改药品字典） │
M4-M13-01 人员档案/分级授权/排班/培训（E-M13） ──→ M4-M16-01 工作量/报表/导出（E-M16）
                                                   └─→ M4-M17-01 质控/RCA/病历质量/上报（E-M17）
M4-QA-01 管理域专项测试与导出脱敏（E-M16/E-M17 + 权限）
```

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M4-M15-01 | 药品目录与麻精五专（字典/五专/基数/日结班结/药房适配器模拟） | 新契约 drug.yaml、m4 模块、V20、前端 | M3 | Review（blocking 已清） |
| M4-M13-01 | 人员档案、分级授权、排班、培训 | 新契约 staff.yaml、m4 模块、V21/V22、前端 | M3-M0-* | Review（blocking 已清） |
| M4-M14-01 | 耗材主数据、出入库、UDI、高值追溯、SPD 模拟 | 新契约 material.yaml、m4 模块、V23、前端 | M4-M15-01 | Blocked |
| M4-M16-01 | 工作量统计、多维报表、导出 | 报表接口/契约、m4 模块、前端 | M4-M13-01、M3 | Blocked |
| M4-M17-01 | 质控指标、不良事件 RCA、病历质量抽查、上报导出 | m4 模块、契约、前端 | M4-M16-01 | Blocked |
| M4-QA-01 | 管理域专项测试、覆盖率、导出脱敏与权限回归 | 测试/文档 | M4-M15-01…M4-M17-01 | Blocked |

## 任务卡要点

### M4-M15-01 药品目录与麻精五专（最高优先）

> **Review（2026-11-14，第二轮）**：契约 `drug.yaml 0.1.0`（10 路径/15 operation，追加
> `/v1/narcotic/departments`）、V20（`m4_drug` 7 表 + 权限 + SYS_PHARMACIST；演示药品移至
> `db/seed/demo/V101`）、m4-api/impl、bootstrap 装配、T-4 收口（m2 用药按字典判定，歧义
> fail-closed）、前端两页 + Vitest/E2E、文档（模块 README/ADR-0016/0018/docs-api/docs-db/
> data-dictionary）已完成；独立评审 B-1/B-2/B-3 与 S-1/S-2/S-3/S-5/S-6 已修。
> 待确认口径见 `docs/modules/m4/README.md` §11 与 ADR-0018“待确认”清单；M4-M14-01 可解锁。
- 目标：药品主数据（名称/规格/单位/分类/麻精标志/是否高危）、别名/编码映射；
  麻精五专（专人/专柜/专册/专账/专处方）记录：专册登记、专柜交接、基数管理、
  日结/班结、处方/使用登记；药房适配器（模拟，EXT-02）与 HIS/药房接口占位。
- 关键：**收口 M3 遗留风险**——麻醉记录用药的 `narcotic` 由服务端按药品字典判定
  （客户端声明仅校验一致性），双人核对/五专留痕引用字典 id；补"舒芬太尼声明 false 被拒"用例。
- 契约：新增 `drug.yaml`（药品/别名/麻精五专/基数/日结班结/适配器状态），additive；
  Java/TS 生成接入（api-contracts 第 6 个 execution、gen-contracts/contract-check DOMAINS）。
- 迁移 V20（m4_drug schema，PG schema 约定见既有 m0/m1/m2）；权限 `m4:drug:read|write`、
  `m4:narcotic:manage` 最小授予（SYS_ADMIN/SYS_PHARMACIST/SYS_ANESTHESIOLOGIST）。
- 前端：药品目录维护、麻精五专登记/交接/基数/日结班结页面；测试：字典校验/麻精判定/
  五专留痕/日结平账/权限 403/导出无 PHI；文档：m4 README、docs/db、docs/api、data-dictionary。

### M4-M13-01 人员档案与分级授权/排班/培训

> **Review（2026-11-14，第二轮）**：契约 `staff.yaml 0.1.1`（10 路径/15 operation，新增
> `/v1/staff/departments` 科室选项 + 双人审批/撤销时点/notApplicable）、V21（`m4_staff` 5 表 +
> 当前视图 + append-only 授权历史 + 排班双唯一冲突 + 权限点 m4:staff:read|write /
> m4:schedule:manage / m4:training:manage）、V22（授权治理列 + `m4:staff:authorize` +
> SYS_AUTH_MANAGER（DEPT）；麻醉医师收敛只读）、
> m4-api/impl（档案/授权校验/排班/培训）、bootstrap 4 个控制器、前端四页（档案/分级授权/
> 人员排班/培训提醒）+ Vitest 17 例 + E2E 1 条、文档（模块 README §14–§22、ADR-0019、
> docs-api §3.14、docs-db §4.16、data-dictionary）已完成。**R1–R5 映射、班次定义、术间多人、
> 换班审批、职称/职级字典等口径未确认，标【待确认】并登记 ADR-0019**；授权校验是 M1/M5
> 集成点（`StaffAuthorizationService.evaluate`），但 M1/M5 侧接入留待对应任务（Q-10）。
> 独立评审 B-1（自授/审批人/双人审批）、B-2（履职角色）、B-3（fail-open）、B-4（回溯版本）
> 与 S-1/S-2/S-3/S-4/S-5/S-6/S-7 已修；待确认清单见 `docs/modules/m4/README.md` §22 与
> ADR-0019（新增 Q-20/Q-21）。M4-M16-01 可解锁。
- 目标：麻醉医师/护士档案（资质、分级授权 R1–R5 与术式/年龄段映射）、排班（班次/术间）、
  培训记录与到期提醒（系统内提醒/报表）；与 m0 用户/角色/数据范围关联。
- 契约：`staff.yaml`（或 auth 扩展，优先新域 staff.yaml）；迁移 V21；权限 `m4:staff:*`；
  分级授权规则可配置（待院方确认口径，标【待确认】）。
- 验收：授权校验（无授权医师不可排某类术式/年龄）、排班冲突、培训到期、越权 403、审计。

### M4-M14-01 耗材/UDI/SPD
- 目标：耗材主数据（UDI/规格/批次/效期）、出入库、高值耗材一物一码追溯（与台次/记录
  关联）、SPD 适配器模拟（EXT-03）；契约 `material.yaml`；V23（V22 已被 M4-M13-01 评审占用）；权限 `m4:material:*`。
- 验收：唯一码全链路追溯（入库→使用→计费）、效期/批次校验、越权、审计、SPD 降级模拟。

### M4-M16-01 工作量与多维报表
- 目标：工作量统计口径（台次/麻醉时长/权重）、多维报表（科室/医师/术式/日期）、导出 CSV
  （脱敏、防公式注入）、与 M2-M6-01 排程分析口径一致；报表契约 additive；权限
  `m4:report:read|export`；导出审计。
- 验收：手算期望、维度过滤、DataScope、CSV 无 PHI/注入防护、性能（≤92 天区间 <10s）。

### M4-M17-01 质控/RCA/病历质量
- 目标：质控指标（结构/过程/结果，口径可配）、不良事件登记与 RCA（时间线/根因/整改/
  闭环）、病历质量抽查（评分表/缺陷项/整改）、上报导出（脱敏，外部上报格式占位）；
  与 M4-M16 报表复用；权限 `m4:qc:*`。
- 验收：指标口径可追溯、RCA 闭环状态机、抽查评分、上报导出脱敏、审计。

### M4-QA-01 管理域专项
- 目标：权限矩阵回归（m4 角色/数据范围）、导出脱敏与 CSV 注入、五专日结/基数一致性、
  UDI 追溯完整性、报表手算对照；发布准入矩阵更新。

## 执行注意
- 迁移从 V20 起（V1–V19 已用，禁止修改）；新域契约需接入 gen-contracts/contract-check/
  api-contracts 生成（DOMAINS/execution/package.json lint）。
- 契约 additive；HEAD baseline 自动检查必须 PASS。
- 临床/财务口径（分级授权映射、统计权重、质控指标）待院方确认，未确认标【待确认】并记 ADR。
- 只用 SYNTHETIC；导出必须脱敏；日志/审计无 PHI。
- 并行任务避免同时跑 Maven（target 竞态）；一次最多 2 个且写域不重叠。
