# m2 — 麻醉信息管理（前端分包）

**一句话职责**：M2 麻醉信息域的浏览器页面、模块内组件与组合式函数；当前已落地「术前访视与评估」（M2-M4-01）。

本目录只放 M2 的页面、模块内组件与组合式函数，不对外导出内部实现。

## 约定

- 跨模块复用只经 `packages/ui`（展示组件）与 `src/api/generated/` / `@anesop/contracts`（契约类型）；
- 禁止引用其他 `src/modules/mX` 的内部文件（本模块只调用自己 `api/m2.ts` 封装的 HTTP 接口）；
- 路由按模块注册，菜单与权限由 `src/router/routes.ts` 与 `src/navigation.ts` 统一装配；
- 合同生成类型禁止手改，改契约后运行 `bash scripts/gen-contracts.sh`。

## 目录

```text
src/modules/m2/
├── api/m2.ts                     anesthesia 0.3.0 类型与 5 个接口
├── utils/preopScores.ts          评分实时预览（与服务端同口径，最终以服务端为准）
├── views/PreopAssessmentView.vue 术前访视与评估页（/m2/preop/:applicationId）
├── views/PreopEntryView.vue      菜单入口说明（/m2/preop）
└── __tests__/                    Vitest：分数展示/最终化/保存失败后成功清错误/403/评分边界
```

页面与路由：

- `/m2/preop`：菜单入口（`m2:preop:read`），说明从手术申请进入；
- 申请列表/详情页均提供「术前访视」入口（`m2:preop:read` 时显示），跳转 `/m2/preop/:applicationId`；
- `/m2/preop/:applicationId`：术前访视与评估页（`m2:preop:read`），分区为病史/气道/ASA/
  评分勾选（RCRI/Apfel/STOP-BANG/DASI）/麻醉计划；实时预览分数与风险等级，最终化需二次确认；
  最终化后只读，可「新建版本」；
- 403/404/网络错误展示契约 `code/message/traceId`，无权限时隐藏保存/最终化按钮。

## 如何测试

```bash
source scripts/env.sh
pnpm -C apps/web exec vue-tsc --noEmit
pnpm test:unit
pnpm e2e
```

## 关键契约

- 契约：`packages/contracts/openapi/anesthesia.yaml` 0.3.0（术前访视与评估）；
  类型经 `@anesop/contracts/anesthesia` 导入，禁止手改生成物。
- 评分口径、切点与【待确认】项见 [`docs/modules/m2/README.md`](../../../../../docs/modules/m2/README.md) §4。
  `utils/preopScores.ts` 是服务端 `PreopScoreCalculator` 的**预览镜像**：改公式必须两端同步。

## 已知限制

- 评分阈值由后端 `anesop.m2.preop.*` 配置（前端预览镜像仍按默认口径）；独立配置表【待确认】。
- 错误提示：操作成功后清空上一次失败的错误条（避免“已保存”与旧错误并存）。
- 数据范围沿用 M1 申请可见性；V2 种子 `SYS_ANESTHESIOLOGIST` 为 SELF 范围，真实使用需 M3
  排班/任务分派范围（详见模块 README §9）。
- 知情同意/三方核查/锁单联动在 M2-M5-01，不在本页。

## 负责人（占位）

- Owner：`@anesop/m2-anesthesia` + `@anesop/clinical-safety`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`

## 状态

M2-M4-01 已落地（2026-11-13）：5 个接口、术前访视页、Vitest 6 用例、E2E 2 条。
