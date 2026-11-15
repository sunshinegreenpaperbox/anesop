# 演练记录归档（docs/deploy/drills）

存放按 [`DRILL-RECORD-TEMPLATE.md`](../DRILL-RECORD-TEMPLATE.md) 填写的演练记录。
命名：`YYYY-MM-DD-<场景>.md`（例：`2026-11-15-health.md`）。

要求：

- 一次演练一份，写清环境、版本、参与人、时间线、度量、问题与签字；
- 只使用 SYNTHETIC 数据；截图/日志不得含真实患者信息、口令、令牌；
- 记录是上线检查清单 §K/§L 与 `docs/acceptance/STATUS.md` 的证据，不是过程草稿；
- 未实际执行的项标 `SKIP（原因）`，不得写成 PASS；EXT-08 相关项写明解除条件。

现有记录：

| 记录 | 场景 | 环境 | 结论 |
|---|---|---|---|
| [`2026-11-15-local-static.md`](2026-11-15-local-static.md) | 本地静态/干跑基线（无 Docker） | 施工机 | PASS（实时项 SKIP，EXT-08） |
