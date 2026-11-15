# ANESOP 上线检查清单（Go/No-Go，M6-M20-05）

> 用法：发布前由发布负责人逐项勾选；每项填**证据**（命令输出、截图、记录路径、签字），
> 不允许"口头确认"。关联验收：[`../acceptance/ACCEPTANCE.md`](../acceptance/ACCEPTANCE.md)
> §A/§B/§I、状态看板 [`../acceptance/STATUS.md`](../acceptance/STATUS.md)、部署手册
> [`DEPLOYMENT.md`](DEPLOYMENT.md)、回滚 [`ROLLBACK.md`](ROLLBACK.md)、数据
> [`DATA-INIT.md`](DATA-INIT.md)、应急预案 [`../runbook/EMERGENCY-PLAYBOOK.md`](../runbook/EMERGENCY-PLAYBOOK.md)。
>
> 判定：**阻断项**任一未完成 → No-Go；非阻断项可带遗留但须登记责任人与期限。
> 生产发布仍须 `release.yml` 审批 + 院方人工签字（上线闸门）。

## A. 组织、变更与人员

- [ ] **A1（阻断）** `release.yml` 变更单已审批，写明版本、范围、回滚方案、影响面
- [ ] **A2（阻断）** 发布窗口为周二/周四 20:00–22:00，临床/信息科已确认停机与公告
- [ ] **A3** 发布负责人、DBA、nginx/运维、临床联络人、信息安全负责人均在岗且电话可达
- [ ] **A4** 上一版本制品、配置、镜像 tag 已保留（回滚用）
- [ ] **A5** 培训完成：管理员/麻醉医师/护士/质控/大屏，签到与考核记录见
      [`../training/README.md`](../training/README.md)

## B. 构建、制品与依赖

- [ ] **B1（阻断）** `./scripts/mvn.sh -B -f apps/api/pom.xml verify` BUILD SUCCESS
- [ ] **B2（阻断）** `pnpm lint`、`vue-tsc --noEmit`、`pnpm test:unit`、`pnpm -C apps/web build` 通过
- [ ] **B3（阻断）** 制品 SHA-256 与 `MANIFEST.txt`（git sha/构建时间/JDK）已记录
- [ ] **B4** 交付包不含 `.env`、证书私钥、`db/seed/demo`（生产包）
- [ ] **B5** 镜像扫描无 HIGH/CRITICAL 可修复项；镜像已固定 `@sha256:`（**EXT-08 未解除前为遗留阻断**）
- [ ] **B6** 依赖白名单/许可证检查通过；无未审批新增依赖

## C. 配置与密钥

- [ ] **C1（阻断）** `SPRING_PROFILES_ACTIVE=prod`，Flyway locations 只含 `db/migration`
- [ ] **C2（阻断）** `ANESOP_JWT_SECRET`/`FIELD_KEY`/`PHONE_PEPPER`/`INPATIENT_PEPPER` 均已注入且非占位
- [ ] **C3（阻断）** 演示账号禁用：`demo-accounts-allowed=false`，生产库 `demo_* = 0`
- [ ] **C4（阻断）** `.env`/`EnvironmentFile` 权限 600，未被 Git 跟踪，未出现在镜像
- [ ] **C5** 采集网关令牌已注入，与用户 JWT 分离；未配置时 ingest 401（fail-closed）
- [ ] **C6** nginx `/api/` 使用 `anesop_no_query` 访问日志，未回退 combined
- [ ] **C7** 限流参数按院方口径确认（默认 20 次/分钟；集群需 Redis 令牌桶）
- [ ] **C8（阻断）** nginx `/api/` 反代前缀与后端路径一致：经 `https://<域名>/api/v1/...` 实测
      非 404。**仓库已修复（2026-11-15）**：`proxy_pass http://anesop_api/;` 剥离前缀，
      `compose-check.sh` 已加回归断言；上线前仍须在预发环境实测登录/接口 200。

## D. 数据初始化与迁移

- [ ] **D1（阻断）** 发布前数据库全备完成且**可读**（`sha256sum -c` + 抽样恢复）
- [ ] **D2（阻断）** `bash scripts/init-data.sh --dry-run` 已复核模式/locations/库名
- [ ] **D3（阻断）** `init-data.sh --mode prod --confirm-prod` 成功，schema 版本记录在发布单
- [ ] **D4（阻断）** 基线核对：角色/权限/字典达到下限；`demo_* = 0`
- [ ] **D5** 组织（SITE/DEPT）、术间、人员、数据范围绑定已按变更单导入并抽查
- [ ] **D6** 首个管理员创建、首次登录已改密；`admin_bootstrap` 已停用（如使用）
- [ ] **D7** 迁移遵循 expand-contract；破坏性变更留到下一次发布

## E. 功能、权限与临床安全

- [ ] **E1（阻断）** 登录 → 首页 → 受保护接口；错误口令 401，越权 403 且有审计
- [ ] **E2（阻断）** 角色/数据范围矩阵抽查：ALL/SITE/DEPT/ROOM/SELF 边界符合预期
- [ ] **E3（阻断）** 关键路径冒烟：申请 → 排程 → 术前访视 → 知情同意 → 三方核查 → 记录单 → 锁单/修订
- [ ] **E4（阻断）** 锁单后只读、修订链与签名摘要正确；并发锁单恰一成功（参考 ACCEPTANCE §H）
- [ ] **E5（阻断）** 麻精五专：双人核对不同人、日结不平 409、台账可对账
- [ ] **E6** 采集：设备登记、在线状态、断网补传幂等（`drill-offline-replay.sh`）
- [ ] **E7** 报表/质控/驾驶舱：口径与手算一致、导出脱敏、10s 轮询稳定
- [ ] **E8** 急诊加台、停改台、绿色通道留痕（如试点启用）

## F. 审计、PHI 与安全

- [ ] **F1（阻断）** 登录/刷新/登出/改密/越权/业务动作成功与失败均入 `sys_audit_log`
- [ ] **F2（阻断）** 日志/审计/错误响应无姓名、身份证、住院号、手机号、真实 SQL 实参
- [ ] **F3（阻断）** 导出 CSV 已脱敏（无患者标识/自由文本），有 BOM 与防注入处理
- [ ] **F4（阻断）** 安全头生效：CSP/HSTS/XFO/nosniff/Referrer/Permissions（443）
- [ ] **F5（阻断）** 无真实患者数据进入 dev/staging/uat；演示/测试均为 SYNTHETIC
- [ ] **F6** 依赖漏洞无未处置 HIGH/CRITICAL；密钥轮换流程已演练

## G. 性能与容量

- [ ] **G1** 关键端点基准：登录/申请列表/时间轴/锁单/报表/质控/看板（参考 ACCEPTANCE §H）
- [ ] **G2** 并发回归：登录、刷新令牌、锁单、事件 seq、报表一致
- [ ] **G3** 数据库连接池、慢查询、索引与磁盘余量满足试点规模（≤30 间、3.5 万例/年）
- [ ] **G4** 队列（RabbitMQ）与对象存储（MinIO）容量/保留策略确认

## H. 备份与恢复

- [ ] **H1（阻断）** 每日全备 + WAL 归档已生效；失败告警可达
- [ ] **H2（阻断）** 恢复演练成功：RTO ≤30 min、RPO ≤5 min，记录见
      [`DRILL-RECORD-TEMPLATE.md`](DRILL-RECORD-TEMPLATE.md)
- [ ] **H3** MinIO 版本化/镜像与单对象恢复演练
- [ ] **H4** 配置、`.env`、证书私钥有独立备份（加密、与备份分离）
- [ ] **H5** 异地副本可读；患者数据不出院（H-01）

## I. 监控、日志与告警

- [ ] **I1（阻断）** API `/actuator/health`、web `/healthz`、DB、Redis、RabbitMQ、MinIO 均健康
- [ ] **I2** Prometheus/Grafana/Loki 可访问（仅 127.0.0.1/跳板机）；仪表盘有数据
- [ ] **I3** P1（业务不可用/采集中断）、P2（错误率/主备切换）、P3（磁盘/队列/证书）通道测试
- [ ] **I4** 日志轮转、Loki 保留、审计 ≥6 个月；磁盘告警阈值设置
- [ ] **I5** 值班表与升级路径已发布，联系人见 [`../runbook/CONTACTS.md`](../runbook/CONTACTS.md)

## J. 时钟、证书与网络

- [ ] **J1（阻断）** NTP 同步；应用/数据库/终端时区一致（Asia/Shanghai）
- [ ] **J2（阻断）** TLS 证书有效且剩余 ≥30 天；`nginx -t` 通过
- [ ] **J3（阻断）** 仅 80/443 对外；数据组件不暴露临床区/DMZ
- [ ] **J4** HSTS 启用前确认全站 HTTPS；CSP 无 inline/eval 脚本

## K. 回滚演练

- [ ] **K1（阻断）** 应用回滚演练 ≤10 分钟，健康/登录/关键路径恢复
- [ ] **K2（阻断）** 回滚后数据无丢失/重复，审计完整；旧应用兼容新 schema
- [ ] **K3** 数据库 PITR 演练 ≤30 分钟（或明确本次不涉及数据库回退）
- [ ] **K4** 回滚决策人、通知链、时间盒已演练并记录

## L. 培训与演练记录

- [ ] **L1（阻断）** 五类角色培训完成并签到：管理员/麻醉医师/护士/质控/大屏
- [ ] **L2** 速查卡已发放到术间/护士站/质控办（[`../training/quick-reference.md`](../training/quick-reference.md)）
- [ ] **L3** 健康检查、备份恢复、断网补传演练记录归档到 `docs/deploy/drills/`
- [ ] **L4** 应急演练至少覆盖：数据库故障、应用不可用、采集断连、疑似数据泄露

## M. Go/No-Go 决策

| 项 | 结论 | 证据/记录 | 签字/时间 |
|---|---|---|---|
| 阻断项全部完成（A1/A2/B1–B3/C1–C4/D1–D4/E1–E5/F1–F5/H1/H2/I1/J1–J3/K1/K2/L1） | 是 / 否 | | |
| 遗留项（含 EXT-08/EXT-01…05）已登记责任人与期限 | 是 / 否 | | |
| 回滚方案与决策人确认 | 是 / 否 | [`ROLLBACK.md`](ROLLBACK.md) | |
| 最终结论 | **Go / No-Go** | | |

> No-Go 时写明阻断项、责任人与最早可重评时间；未关闭的 P0（记录丢失、签名失效、越权、泄露）
> 一律不得发布。
