# m2 — 麻醉信息管理（前端分包）

**一句话职责**：M2 麻醉信息域的浏览器页面、模块内组件与组合式函数；已落地术前访视与评估
（M2-M4-01）、知情同意与三方核查（M2-M5-01）、麻醉记录单/时间轴/用药/出入量（M3-M2-01）、
术中预警与抢救（M3-M2-02）、锁单/修订/CA 占位签名（M3-M2-03）、PACU 复苏/转出（M3-M10-01）
与术后随访/镇痛（M3-M11-01）。

本目录只放 M2 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/` / `@anesop/contracts`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件（本模块只调用自己 `api/m2.ts` 封装的 HTTP 接口）；
- 路由按模块注册，菜单与权限由 `src/router/routes.ts` 与 `src/navigation.ts` 统一装配；
- 合同生成类型禁止手改，改契约后运行 `bash scripts/gen-contracts.sh`。

## 目录

```text
src/modules/m2/
├── api/m2.ts                     anesthesia 0.10.0 类型与模块接口封装
├── utils/preopScores.ts          评分实时预览（与服务端同口径，最终以服务端为准）
├── views/PreopAssessmentView.vue 术前访视与评估页（/m2/preop/:applicationId）
├── views/PreopEntryView.vue      菜单入口说明（/m2/preop）
├── views/ConsentView.vue         知情同意（/m2/consent/:applicationId）
├── views/ThreeWayCheckView.vue   三方核查（/m2/three-way/:applicationId）
├── views/RecordView.vue          麻醉记录单/时间轴/预警/抢救/锁单修订（/m2/records/:scheduleId）
├── views/AlertRuleConfigView.vue 预警规则配置（/m2/alert-rules）
├── views/PacuView.vue            PACU 入室/Aldrete 评分表与时间序列/转出 SBAR（/m2/pacu/:recordId）
├── views/FollowupView.vue        术后随访（24/48h、NRS/PONV/并发症/反馈）与镇痛 PCA
│                                 （/m2/followups/:recordId）
└── __tests__/                    Vitest（术前访视/同意/核查/记录/锁单/PACU/随访）
```

页面与路由：

- `/m2/preop`、`/m2/consent`、`/m2/three-way`、`/m2/records`、`/m2/alert-rules`：菜单入口
  说明页（按权限显示）；
- `/m2/preop/:applicationId`：术前访视与评估（病史/气道/ASA/评分/计划/最终化）；
- `/m2/consent/:applicationId`：知情同意（模板/创建/签署/拒绝/撤销，CA 占位 SIMULATED）；
- `/m2/three-way/:applicationId`：三方核查（Time-out/Sign-out/标本/植入物/问题项 + 版本）；
- `/m2/records/:scheduleId`：麻醉记录单（时间轴/用药/出入量/预警/抢救/锁单/修订/版本对比）；
  页面顶部「复苏记录（PACU）」按钮跳转 `/m2/pacu/:recordId`；
- `/m2/followups/:recordId`：术后随访与镇痛（24/48h 选择、NRS 0–10、PONV 0–3、并发症多选 +
  说明、患者反馈脱敏提示、随访人；镇痛 PCIA/PCEA 参数 bolus/background/lockout、效果与调整
  说明）；随访与镇痛 append-only，更正只能新增；记录页「术后随访」按钮按 `m2:followup:read`
  显示；
- `/m2/pacu/:recordId`：PACU 入室（生命体征/意识/疼痛/恶心/气道**不预填默认值**，关键体征
  逐项显式录入，Aldrete 五项未选齐时「记录评估」禁用并提示）、评分表（实时总分，提交后以
  服务端计算为准）、时间序列与转出对话框（去向 + SBAR + 达标确认；展示服务端
  `latestAldreteTotal/latestAssessmentAt`，不使用本地未提交分值）；阈值不足 409 时展示服务端
  未满足条件码，前端不自行判定能否转出；
- 403/404/网络错误展示契约 `code/message/traceId`，无权限时隐藏写入按钮。

## 如何测试

```bash
source scripts/env.sh
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit
pnpm e2e
```

## 关键契约

- 契约：`packages/contracts/openapi/anesthesia.yaml` 0.10.0（术前访视/同意/核查/记录单/
  预警/锁单修订/PACU/术后随访与镇痛）；类型经 `@anesop/contracts/anesthesia` 导入，禁止手改生成物。
- 评分口径、切点与【待确认】项见 [`docs/modules/m2/README.md`](../../../../../docs/modules/m2/README.md) §4。
  `utils/preopScores.ts` 是服务端 `PreopScoreCalculator` 的**预览镜像**：改公式必须两端同步。

## 已知限制

- 评分阈值由后端 `anesop.m2.preop.*` 配置（前端预览镜像仍按默认口径）；独立配置表【待确认】。
- 错误提示：操作成功后清空上一次失败的错误条（避免“已保存”与旧错误并存）。
- 数据范围沿用 M1 申请可见性；V2 种子 `SYS_ANESTHESIOLOGIST` 为 SELF 范围，真实使用需 M3
  排班/任务分派范围（详见模块 README §9）。
- 知情同意/三方核查/锁单联动见 M2-M5-01 与 M3-M2-03 页面；PACU 阈值与枚举【待确认】
  （`docs/modules/m2/README.md` §15）。

## 负责人（占位）

- Owner：`@anesop/m2-anesthesia` + `@anesop/clinical-safety`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`

## 状态

M2-M4-01 已落地（2026-11-13）：5 个接口、术前访视页、Vitest 6 用例、E2E 2 条。
