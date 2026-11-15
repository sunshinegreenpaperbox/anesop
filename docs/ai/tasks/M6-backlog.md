# M6 任务卡包（试点上线准备，E-M20）

> 目标：2027-06-07 前完成部署清单、数据初始化、培训材料、应急预案、演练脚本（E-M20）。
> 使用方式同前：总控按依赖派子代理；核心域（权限/审计/安全）独立 review；写域不重叠；
> 不运行容器/不部署/不写真实数据/不 commit。
> 里程碑退出准则：`./scripts/mvn.sh -B -f apps/api/pom.xml verify` BUILD SUCCESS；
> `bash scripts/compose-check.sh` PASS；初始化/演练脚本可用；上线检查清单可勾选；
> 培训/演练材料齐备；上线与数据闸门仍由人工签字。

## 任务总览

| ID | 任务 | 写域 | 依赖 | 状态 |
|---|---|---|---|---|
| M6-M20-01 | 部署清单与上线手册（环境/配置/构建打包/发布/验证） | `docs/deploy/**`、`deploy/compose/**`（仅 env 注释） | M5、EXT-08 | Done（镜像实跑受限） |
| M6-M20-02 | 数据初始化脚本与生产/演示隔离 | `scripts/init-data.sh`、`docs/deploy/DATA-INIT.md` | M5、db/migration | Done |
| M6-M20-03 | 培训材料（管理员/医师/护士/质控/大屏 + 速查卡） | `docs/training/**` | M5 | Done |
| M6-M20-04 | 应急预案（DB/应用/采集/CA/网络分区/泄露） | `docs/runbook/**` | M5 | Done |
| M6-M20-05 | 上线检查清单 + 演练脚本/记录模板 | `docs/deploy/GO-LIVE-CHECKLIST.md`、`scripts/drill-*.sh` | M6-M20-01…04 | Done（实时演练待环境） |
| M6-QA-01 | 上线准备自检（构建/静态校验/链接/写域） | 不限（只读验证） | 上述 | Done |

## M6-M20-01 部署清单与上线手册

- 目标：把"发布当天怎么执行"固化为可复制命令：环境前置、配置项（secrets 来源/
  `ANESOP_*`/prod fail-closed/演示账号禁用）、本地构建与 tar 打包（Docker 不可用）、
  迁移→后端→前端→nginx→验证、10 分钟回滚入口。
- 交付：
  - `docs/deploy/DEPLOYMENT.md`（新增）：两种部署形态、前置、配置清单、§4 本地构建/tar 打包、
    §5 发布步骤、§6 发布记录；
  - `docs/deploy/ROLLBACK.md`（新增）：应用/数据库回滚、时间盒、验证、签字；
  - `docs/deploy/GO-LIVE-CHECKLIST.md`（新增）：A–M 可勾选清单；
  - `docs/deploy/DRILL-RECORD-TEMPLATE.md` + `docs/deploy/drills/`（新增）；
  - `docs/deploy/README.md`：§0 文档索引、配置项补 `ANESOP_FIELD_KEY_ID`/网关令牌、
    回滚/检查清单交叉链接；
  - `deploy/compose/docker-compose.yml` + `.env.example`：api 注入
    `ANESOP_FIELD_KEY/FIELD_KEY_ID/PHONE_PEPPER/INPATIENT_PEPPER/M2_ACQUISITION_GATEWAY_TOKEN`
    （生产 fail-closed 所需；不改服务拓扑）。
- 验收：`compose-check` 35 PASS；配置项与 `application.yml`/`application-prod.yml` 一致；
  Docker 路径标 EXT-08，未宣称已实跑。
- 遗留：镜像 build/save/load、Trivy、`docker compose config/up` 未执行（EXT-08）；
  **nginx `/api/` 前缀与后端 `/v1` 路径需上线前实测/修复**（见 §风险）。

## M6-M20-02 数据初始化

- 目标：区分生产基线与演示数据；`scripts/init-data.sh` 支持模式/参数化 URL/干跑/失败非零；
  prod 只加载 `db/migration`，demo 委托 `seed-demo`。
- 交付：`scripts/init-data.sh`（新增）、`docs/deploy/DATA-INIT.md`（新增）。
- 关键行为：
  - `--dry-run` 不连库、不跑 Maven，打印 locations 与安全闸门；
  - prod：`--confirm-prod` 必须；拒绝库名含 demo/dev/test/local、拒绝 dev/test profile；
    Flyway 后核对角色 ≥6/权限 ≥17/字典类型 ≥6/字典项 ≥22、`demo_* = 0`；失败退出 7；
  - demo：委托 `seed-demo.sh`（V100/V101，SYNTHETIC），首次登录强制改密；
  - 退出码 0/2/3/4/5/6/7 已在文件头约定。
- 验收：`bash scripts/init-data.sh --dry-run` 可用；`bash -n` 通过；
  无真实库连接（本机无 PG/Docker，EXT-08）。
- 遗留：真实迁移/基线核对需在演练/生产库执行并签字；组织/术间/首个管理员按变更单导入。

## M6-M20-03 培训材料

- 目标：每角色 1 份 + 速查卡，含截图占位、编号步骤、常见错误与处理；只使用 SYNTHETIC。
- 交付：`docs/training/README.md`（课程/考核/纪律）、`admin.md`、`anesthesiologist.md`、
  `nurse.md`、`qc.md`、`dashboard.md`、`quick-reference.md`。
- 验收：五个角色路径与路由/权限矩阵一致（M0/M1/M2/M4/M5）；常见错误覆盖 403/404/409/429/
  锁单/日结/导出/大屏停更；截图占位统一标注"上线前补图、禁止真实患者"。
- 遗留：实际授课、签到、考核与截图需院方在演示环境完成；培训记录作为上线检查清单 §L 证据。

## M6-M20-04 应急预案

- 目标：六类场景（DB 故障/应用不可用/采集断连/CA 不可用/网络分区/疑似泄露）各有
  检测信号、止血、回滚/切换、通知链、恢复验证、留痕；故障处置与联系人/升级路径独立成册。
- 交付：`docs/runbook/README.md`、`EMERGENCY-PLAYBOOK.md`、`FAULT-PLAYBOOK.md`、
  `CONTACTS.md`。
- 验收：每场景六段齐全；P0 30 分钟上报与 2 小时响应对齐 `AGENTS.md`；联系人只放角色占位，
  无真实姓名/手机号；与回滚/备份手册交叉链接有效。
- 遗留：通知链实测、真实 CA/网络场景需院方环境；联系人由院方在受控附件填写。

## M6-M20-05 上线检查清单与演练脚本

- 目标：可勾选 Go/No-Go 清单；演练脚本可在无 Docker 下计划/静态执行，有环境时实测。
- 交付：
  - `docs/deploy/GO-LIVE-CHECKLIST.md`：A 组织/B 构建/C 配置/D 数据/E 功能权限/F 审计 PHI/
    G 性能/H 备份/I 监控/J 时钟证书/K 回滚/L 培训 + M 决策；
  - `scripts/drill-health.sh`：健康/时钟/证书/DB TCP；`--dry-run`/`--record`；
  - `scripts/drill-backup-restore.sh`：pg_dump→恢复→行数比对→RTO；目标库名含 drill/restore；
  - `scripts/drill-offline-replay.sh`：同 batchKey 双报，断言 `duplicateBatch=true/acceptedRows=0`；
  - `docs/deploy/drills/2026-11-15-local-static.md`：本地静态/干跑记录（实时项 SKIP）。
- 验收：三脚本 `--help` 正常、`--dry-run` 不产生外部副作用；`bash -n` 通过；
  健康脚本在无地址时 PASS 2/SKIP 3（本机实测）；备份/补传脚本在无工具/无 API 时给出可操作提示。
- 遗留：实时演练（回滚、PITR、补传、健康）需预发/演练环境；本机无 PG/Docker（EXT-08）。

## M6-QA-01 上线准备自检

| 项 | 结果 | 证据 |
|---|---|---|
| 后端构建未破坏 | **BUILD SUCCESS**：1172 用例，0 failure/0 error（2026-11-15） | `./scripts/mvn.sh -B -f apps/api/pom.xml verify`（exit 0） |
| Compose/nginx 静态校验 | PASS 34 / FAIL 0 / SKIP 1 | `bash scripts/compose-check.sh` |
| 初始化干跑 | PASS | `bash scripts/init-data.sh --dry-run` |
| 演练脚本帮助/干跑 | PASS | 三脚本 `--help`/`--dry-run` |
| 文档相对链接 | 97 条，0 broken | 新文档相对链接抽查（Python 脚本） |
| 写域 | 只改 M6 写域 | `git status` |

## 风险与遗留（需院方/人工）

| 项 | 影响 | 解除条件 | 责任 |
|---|---|---|---|
| EXT-08 Docker/镜像扫描 | 镜像 build/Trivy/Compose 实跑未验证 | 具备 Docker 24+ 的宿主机；补 digest/扫描并回填 | 信息科/CI |
| nginx `/api/` 前缀 | 生产经 nginx 访问可能 404 | `proxy_pass http://anesop_api/;` 或后端 `context-path=/api`，`nginx -t` + 真实登录 | 部署/后端 |
| EXT-05 CA | 真实签名未联调 | CA 厂商 SDK/证书；补签与验证 | 医务处/厂商 |
| EXT-01…04 | HIS/药房/SPD/设备真实接口 | 院方测试环境 | 信息科 |
| 实时演练 | 回滚/PITR/补传未实测 | 预发/演练环境；按模板记录签字 | 发布负责人/DBA |
| 上线/数据闸门 | 生产发布与真实数据迁移 | `release.yml` + 院方人工签字 | 项目经理/信息科 |

## 验证命令（本轮）

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f apps/api/pom.xml verify
bash scripts/compose-check.sh
bash scripts/init-data.sh --dry-run
bash scripts/drill-health.sh --dry-run
bash scripts/drill-backup-restore.sh --dry-run
bash scripts/drill-offline-replay.sh --dry-run
```
