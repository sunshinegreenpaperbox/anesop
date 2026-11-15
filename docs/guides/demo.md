# 演示脚本（ACCEPTANCE C：合成数据端到端）

> 对应 [`docs/acceptance/ACCEPTANCE.md`](../acceptance/ACCEPTANCE.md) C 项：初始化合成数据 →
> 登录 → 排程 → 术前 → 术中 → 复苏 → 随访 → 统计 → 质控 → 驾驶舱。
> S0 交付了 M0 公共支撑与采集网关 POC；M1–M8 业务模块已按契约落地，全模块演示数据
> 由 [`scripts/seed-demo-data.sh`](../../scripts/seed-demo-data.sh) 通过真实 REST API 灌入，
> 见第 6 节。所有数据为 `SYNTHETIC` 合成数据，禁止使用真实患者信息。

## 0. 前置条件与可用性

| 依赖 | 要求 | 本机现状 |
|---|---|---|
| PostgreSQL 16 | 可连接的空库或已迁移库 | 需自备；Docker 不可用（EXT-08） |
| API | `api-bootstrap` 以 `dev` profile 启动，监听 8080 | 需 PG；见步骤 2 |
| Web | `pnpm -C apps/web dev`，5173；`/api` 代理到 8080 | 可独立启动（无后端时登录会失败） |
| 采集网关 | 可选；S0-09 POC，8081/19090 | 需 PG（V4 表） |
| 演示账号 | `demo_admin` / 初始口令见 [`docs/db/seed.md`](../db/seed.md) | 由 `scripts/seed-demo.sh` 写入 |

> 本机没有 Docker 时，**seed-demo + API 启动需要你自备一个 PostgreSQL 16**
> （本机安装或远程 dev 库）。`scripts/check.sh` 不需要 PG（测试用嵌入式 PG）。

## 1. 初始化合成数据（ACCEPTANCE C-1，已可用）

```bash
source scripts/env.sh

# 方式 A：本地 PG（默认读 ANESOP_DB_URL / ANESOP_DB_USER / ANESOP_DB_PASSWORD）
export ANESOP_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_DB_USER=anesop
export ANESOP_DB_PASSWORD=anesop
bash scripts/seed-demo.sh

# 方式 B：显式连接串
bash scripts/seed-demo.sh --url jdbc:postgresql://127.0.0.1:5432/anesop --user anesop --password anesop

# 方式 C：Docker 可用时（本机 EXT-08 会明确报不可用）
bash scripts/seed-demo.sh --compose
```

预期输出（首次）：

```text
seed-demo: 目标库 jdbc:postgresql://127.0.0.1:5432/anesop（用户 anesop）
seed-demo: Flyway locations = db/migration + db/seed/demo（仅 dev/demo；生产不得包含后者）
...
seed-demo: OK
  本次执行的迁移数：5（0 = 已是最新，幂等生效）
  当前 schema 版本：100
  演示账号：demo_admin（演示管理员（合成）；mustChange=true；status=1）
  初始口令：见 docs/db/seed.md（禁止写入工单、日志或截图）
  必须改密：must_change_password=true；首次登录后先改密，再重新登录。
```

再执行一次应显示 `本次执行的迁移数：0`，账号不会重复插入（幂等）。迁移版本随仓库演进而增加，
以实际输出为准；`V1…V4` 来自 `db/migration`，`V100` 来自 `db/seed/demo`。

## 2. 启动 API 与前端（已可用）

```bash
source scripts/env.sh

# 终端 1：后端（需 PG；dev profile 允许演示账号与进程内 JWT 密钥）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am -DskipTests package
java -jar apps/api/api-bootstrap/target/api-bootstrap-*.jar --spring.profiles.active=dev
# 或一键启停本地演示 PG(55432)+API(8080)：bash scripts/demo-local.sh start
# 该脚本默认注入仅 dev 使用的采集网关令牌 demo-gateway-token（生产禁止使用默认值）

# 健康检查（预期 {"status":"UP",...}）
curl -s http://localhost:8080/actuator/health

# 终端 2：前端
pnpm install --frozen-lockfile
pnpm -C apps/web dev
# 浏览器打开 http://localhost:5173 → 登录页
```

如果后端未连上 PG，启动会因 Flyway/数据源失败退出；先完成第 1 步并确认
`bash scripts/dev-check.sh` 的 5432 检查为 PASS。

## 3. 登录与改密（ACCEPTANCE C-2 的 M0 部分，已可用）

> 首次登录 `must_change_password=true`，服务端只允许 `/v1/auth/me`、`/v1/auth/logout`、
> `/v1/auth/password`，其余接口 403；改密后需重新登录。

```bash
BASE=http://localhost:8080

# 3.1 登录（初始口令见 docs/db/seed.md）
LOGIN=$(curl -s -X POST "$BASE/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Anesop@2026"}')
printf '%s\n' "$LOGIN" | python3 -m json.tool
ACCESS=$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')

# 预期：HTTP 200；user.username=demo_admin；roles 含 SYS_ADMIN；
#       user.mustChangePassword=true；expiresIn=900；tokenType=Bearer

# 3.2 首次登录强制改密（旧口令 → 新口令；新口令 12–128 位，至少三类字符）
curl -s -o /dev/null -w 'password change: %{http_code}\n' \
  -X POST "$BASE/v1/auth/password" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"oldPassword":"Anesop@2026","newPassword":"Anesop@2026#Demo"}'
# 预期：204

# 3.3 用新口令重新登录并确认 mustChangePassword=false
LOGIN=$(curl -s -X POST "$BASE/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Anesop@2026#Demo"}')
ACCESS=$(printf '%s' "$LOGIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
curl -s "$BASE/v1/auth/me" -H "Authorization: Bearer $ACCESS" | python3 -m json.tool
# 预期：200；permissions 含 m0:auth:read/m0:auth:write；mustChangePassword=false

# 3.4 会话分页（SYS_ADMIN 拥有 m0:auth:read）
curl -s "$BASE/v1/auth/sessions?page=1&size=10" -H "Authorization: Bearer $ACCESS" | python3 -m json.tool
# 预期：200；items 至少 1 条；meta.page/size/total 完整

# 3.5 刷新与登出（可选）
# curl -s -X POST "$BASE/v1/auth/refresh" -H 'Content-Type: application/json' -d '{"refreshToken":"..."}'
# 预期：200 且返回新 accessToken/refreshToken；旧 refreshToken 立即失效
# curl -s -o /dev/null -w '%{http_code}\n' -X POST "$BASE/v1/auth/logout" -H "Authorization: Bearer $ACCESS"
# 预期：204（幂等）
```

浏览器等价路径：`http://localhost:5173` → 登录 → 强制改密页 → 首页 → 右上角菜单
（按权限过滤）→ 会话管理页。S0 只有 M0 页面，M1–M8 菜单为占位。

## 4. 审计追溯（ACCEPTANCE C-7，已可用：SQL 查询）

审计没有对外查询接口（M1 起按 `docs/modules/m0/README.md` 规划），当前直接查库：

```bash
# 需要本机有 psql；没有时用任意 JDBC 客户端执行同一 SQL
psql "postgresql://anesop:anesop@127.0.0.1:5432/anesop" -c "
  select occurred_at, actor_name, action, resource_type, resource_id, result, trace_id
  from m0_platform.sys_audit_log
  order by occurred_at desc limit 10;"
```

预期：能看到本次演示的 `LOGIN`、`PASSWORD_CHANGE`、`TOKEN_REFRESH`/`LOGOUT` 记录；
失败登录/越权也会留痕（`result=0`）。审计表只追加：`UPDATE/DELETE/TRUNCATE` 会被触发器拒绝
（SQLState 55000），可作为演示的“不可篡改”证据：

```bash
psql "postgresql://anesop:anesop@127.0.0.1:5432/anesop" -c \
  "update m0_platform.sys_audit_log set action='X' where true;"   # 预期：ERROR 55000
```

## 5. 术中设备采集 POC（ACCEPTANCE C-4 的可运行子集，已可用）

> 仅验证「设备 → 网关 → 数据库」链路与断网补传/幂等，不是完整麻醉记录单。

```bash
source scripts/env.sh
export ANESOP_GATEWAY_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_GATEWAY_DB_USER=anesop
export ANESOP_GATEWAY_DB_PASSWORD=anesop

# 打包并启动网关（8081 HTTP / 19090 TCP；缓冲目录默认 ./data/buffer）
./scripts/mvn.sh -B -f services/device-gateway/pom.xml -DskipTests package
java -jar services/device-gateway/target/device-gateway-*.jar

# 另一终端：推 60 帧合成设备数据（1 秒 1 帧）
java -cp services/device-gateway/target/device-gateway-*.jar \
  com.anesop.gateway.simulator.SimulatedDevice 127.0.0.1 19090 SIM-MON-01 60 1000

curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8081/actuator/metrics/anesop.gateway.frames.received
```

断网补传演示：停掉 PG 或断开网络 → 继续推帧（网关只落本地缓冲，不阻塞）→ 恢复 PG →
等待上传重试 → 查询行数与唯一键：

```bash
psql "postgresql://anesop:anesop@127.0.0.1:5432/anesop" -c "
  select device_id, count(*), min(measured_at), max(measured_at)
  from m2_anes.anesthesia_vital where device_id='SIM-MON-01' group by device_id;"
# 预期：行数等于推送帧数（重复推送不增加，唯一键 device_id+seq+measured_at 幂等）
```

## 6. 全模块演示数据一键灌入（已可用）

> 用真实 REST API（非 SQL 直插）写入覆盖各环节的 `SYNTHETIC` 数据，走业务校验、状态机、
> 审计与消息通知，适合演示前快速铺数据。仅用于本地/演示库，生产/预发/uat 禁止执行。

### 6.1 用法

```bash
# 前置：API 已启动且 /actuator/health = UP（见第 2 节）
bash scripts/seed-demo-data.sh --dry-run        # 只打印计划，不写数据
bash scripts/seed-demo-data.sh                  # 默认 http://127.0.0.1:8080
bash scripts/seed-demo-data.sh --base-url http://127.0.0.1:8080
bash scripts/seed-demo-data.sh --section alerts # 只跑「设备与告警」段（4–10 秒，复用已有进行中记录）

# 口令可覆盖（默认值仅本地演示）：
DEMO_ADMIN_PASSWORD='当前口令' \
DEMO_ADMIN_NEW_PASSWORD='Anesop@2026#Demo' \
bash scripts/seed-demo-data.sh
```

脚本先 `curl /actuator/health`，再以 `demo_admin` 登录；若 `mustChangePassword=true` 自动改密
并重新登录（旧/新口令可用环境变量覆盖）。`demo_self` 仅用于消息中心未读校验，同样支持
`DEMO_SELF_PASSWORD` / `DEMO_SELF_NEW_PASSWORD`。数量可用 `SEED_HIS_IMPORT_COUNT`、
`SEED_APP_COUNT`、`SEED_PREOP_COUNT`、`SEED_DRUG_COUNT`、`SEED_MATERIAL_COUNT`、
`SEED_STAFF_COUNT` 调整（低于流程下限时自动抬到下限）。

### 6.2 生成内容（默认值）

| 模块 | 内容 |
|---|---|
| M1 申请 | HIS 模拟导入 3 条（`MOCK_HIS`/`simulated=true`）+ 手工申请 15 条，覆盖择期/限期/急诊、多术式/年龄；2 条取消为 `CANCELLED` |
| M1 排程 | ≥8 个台次，覆盖今天/明天/后天、多个术间与序号；改台（PUT）、停台、急诊抢占加台、冲突检测（409）+ 改期解决、台次取消各 1 条 |
| M2 术前 | 访视评估 6 份（ASA I–IV、Mallampati I–IV、RCRI/Apfel/STOP-BANG/DASI 组合），1 份 finalize；知情同意 10 份（签署 8、拒绝 1、签署后撤销 1）；三方核查 8 个申请 + 1 个申请多版本（先 Time-out、再补 Sign-out） |
| M2 术中 | 记录单 5 张：锁定+修订 1、锁定 2、进行中 1、草稿 1；事件覆盖麻醉开始/诱导/插管/切皮/拔管/结束；麻精用药 1（第二核对人 `demo_self`）+ 普通用药 1；输液/出入量各 1；抢救 1；告警评估 2 次；锁单后写入被 409 拒绝（状态机验证） |
| M2 复苏/随访 | 复苏 3 份（Aldrete 10/9/9，1 份转出 WARD）；随访 3 份（H24/H48、NRS/PONV）；PCIA/PCEA 参数各 1 |
| M2 设备/告警 | 设备 2 台（监护仪/麻醉机）绑定进行中记录术间；网关令牌 ingest 40 帧合成体征（含 SpO2 88%、HR 134 样本）；触发 `SPO2_LOW`/`HR_HIGH` 告警；记录单 `vitalSigns` 非空（见 6.5） |
| M4 药品/麻精 | 新增药品 5（麻精 2、高危/镇静/升压等，含别名）；麻精专册、柜交接、基数、日结各 1（日结平账 `5+2-2-0=5`） |
| M4 人员 | 人员档案 4、分级授权 R1–R5（R1/R2 双人审批）、排班 4、培训 4（1 条 20 天内到期）；另建 2 个 SYNTHETIC 审批账号 `demo_approver_1/2`（SYS_ADMIN，仅本地） |
| M4 耗材 | 主数据 5、出入库 8（高值耗材只走 UDI，不允许裸数量）、UDI 5（1 已使用并绑定进行中记录）、SPD 模拟同步 1 |
| M4 报表/质控 | 工作量、多维各查询 1；锁定快照 1；不良事件 + RCA + 复核 1；病历抽查 + 整改 + 闭环 1 |
| M5 看板 | `realtime` / `kpi` / `risk` / `resources` 各查询 1 |
| 消息中心 | 停台/改台/急诊触发通知 outbox，轮询 `demo_self` 未读消息 ≥1 |

### 6.3 可重复执行与边界

- 业务数据（申请、台次、记录、随访、质控等）**每次追加**，单号带批次后缀（`SYNTHETIC-…-<batch>`）；
  主数据（药品/人员/耗材/审批账号）按编码/用户名去重，已存在则跳过。
- 排程时段有限：目标日/术间已满时脚本自动顺延到 +3~+5 天的空闲时段，保证记录/复苏等后续流程不断；
  连 +5 天也无空位时按「跳过」计数。反复灌入会不断累积数据，需要干净的全量演示时请重建演示库（见第 8 节）。
- 已知接口边界（脚本不绕过）：当前 API 没有申请 `ACCEPTED`/`SCHEDULED` 流转端点，排程后申请仍为
  `SUBMITTED`，脚本覆盖 `SUBMITTED` 与 `CANCELLED`；API 未注入网关令牌时设备 ingest 返回 401、
  告警仍为 0（接口 fail-closed），`demo-local.sh start` 默认注入演示令牌（仅 dev，见 6.5）；
  RCA 复核仅到阶段 1（`rectificationCompleted=false` 为服务端约束）。
- 失败会打印脱敏后的响应并最终非零退出；不输出 token/口令/患者标识。

### 6.4 快速抽查

```bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -s -X POST "$BASE/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Anesop@2026#Demo"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s "$BASE/v1/scheduling/schedules?date=$(date +%F)&page=1&size=50" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head
curl -s "$BASE/v1/reports/workload?from=$(date +%Y-%m-01)&to=$(date +%F)&dimension=DEPT" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head
curl -s "$BASE/v1/dashboard/realtime" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head
curl -s "$BASE/v1/qc/adverse-events?page=1&size=5" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head
```

记录单按台次查询：`GET /v1/anesthesia/records?scheduleId=<台次ID>`（契约必填），再用
`/events`、`/pacu-records?recordId=`、`/followups?recordId=` 查看时间轴、复苏与随访。

### 6.5 设备与告警演示（采集 ingest + 术中告警）

> 为什么单独做：M2 告警由「采集 ingest 成功提交后的领域事件」实时评估落库，没有体征样本时
> `m2_anes.alert` 恒为 0。本段通过真实设备接口补齐“设备 → 体征 → 告警”链路。

```bash
# 推荐：只跑设备与告警段（4–10 秒，不重复灌业务数据）
bash scripts/seed-demo-data.sh --section alerts
# 全量灌入也包含本段（位于复苏/随访之后，保证记录 startedAt 已在过去）
bash scripts/seed-demo-data.sh
```

脚本行为（全部走 REST API，不使用 SQL 直插）：

1. 复用一个 `IN_PROGRESS` 麻醉记录单（按台次扫描；没有则在最近的空闲台次新建并记录
   `ANESTHESIA_START`），读取其记录单与术间；
2. `POST /v1/devices` 登记 2 台设备（`GW-DEMO-01/SIM-MON-DEMO-01` 监护仪、
   `GW-DEMO-01/SIM-VENT-DEMO-01` 麻醉机）并绑定该术间（需 `m2:device:write`，`demo_admin` 有）；
3. `POST /v1/devices/{gatewayId}/{deviceId}/ingest` 上传 40 帧合成体征
   （HR/SpO2/NIBP/EtCO2/体温，含 SpO2 88%、HR 134、MAP 低值样本），header
   `X-Gateway-Token` 取 `ANESOP_M2_ACQUISITION_GATEWAY_TOKEN`（默认 `demo-gateway-token`）；
4. `POST /v1/anesthesia/records/{recordId}/alerts/evaluate` 做补偿/幂等评估（正常链路由 ingest
   提交后的领域事件实时评估，手动评估通常为 0）；
5. 断言并输出：`GET /records/{recordId}/alerts` 非空（演示通常得到 `SPO2_LOW`/`HR_HIGH`），
   `GET /records/{recordId}` 的 `vitalSigns` 非空；任一为空则非零退出。

网关令牌用途与边界：

| 项 | 说明 |
|---|---|
| 变量 | `ANESOP_M2_ACQUISITION_GATEWAY_TOKEN`（映射 `anesop.m2.acquisition.gateway-token`） |
| 用途 | 仅用于采集网关 `ingest` 的 `X-Gateway-Token`，与用户 JWT 分离；不落日志/审计/错误响应 |
| 本地默认 | `scripts/demo-local.sh start` 默认注入 `demo-gateway-token`（仅 dev/demo；可用同名环境变量覆盖） |
| 生产 | 必须由院方密钥托管注入随机值（如 `openssl rand -base64 32`）；**未注入即 401（fail-closed）**，禁止使用默认值 |

若 `ingest` 返回 401（API 启动时未注入令牌），脚本会打印明确提示；本地先
`bash scripts/demo-local.sh stop && bash scripts/demo-local.sh start` 重启（只重启，不删数据），
再用 `bash scripts/demo-local.sh status` 看令牌探针（`404=令牌通过且设备未登记`，`401=未注入/不匹配`）。

手工抽查（把 `<recordId>` 换成脚本输出的目标记录单）：

```bash
BASE=http://127.0.0.1:8080
TOKEN=$(curl -s -X POST "$BASE/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"demo_admin","password":"Anesop@2026#Demo"}' | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -s "$BASE/v1/anesthesia/records/<recordId>/alerts" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
curl -s "$BASE/v1/anesthesia/records/<recordId>" -H "Authorization: Bearer $TOKEN" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print("vitalSigns:", len(d["vitalSigns"]))'
curl -s "$BASE/v1/devices" -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## 7. 完整业务流程（M1/M2 起逐段可用）

| # | ACCEPTANCE C 步骤 | 契约/入口（已存在） | 现状 | 预期结果（落地后） |
|---|---|---|---|---|
| 2 | 排程工作台新建/调整台次、制造并解决冲突 | `scheduling.yaml` | **已可用**（第 6 节脚本覆盖改台/停台/急诊/冲突） | 冲突 409 `SCHEDULE_CONFLICT`，调整后 200 |
| 3 | 术前访视 → 风险评分 → 知情同意 → 三方核查 | `anesthesia.yaml`、`patient.yaml` | **已可用** | 访视单保存、评分回显、同意签署/拒绝/撤销、三方核查多版本 |
| 4 | 术中开始记录 → 设备推送 → 预警 → 用药/事件 → 锁单 → 修订 | `anesthesia.yaml` + 采集网关 | **已可用**；`seed-demo-data.sh --section alerts` 可一键补齐设备/体征/告警（需网关令牌，`demo-local.sh` 默认注入） | 锁定后不可改；修订留痕、版本递增；锁单前校验同意/三方核查 |
| 5 | 复苏 → 转出 → 术后随访 | `anesthesia.yaml` | **已可用** | 复苏 Aldrete、转出、H24/H48 随访与镇痛参数 |
| 6 | 质控指标与工作量报表 → 驾驶舱大屏 | `report.yaml`、`qc.yaml`、`dashboard.yaml` | **已可用** | 工作量/多维报表、锁定快照、质控闭环、看板四接口 |
| 7 | 审计日志查询任意关键操作 | 表 `m0_platform.sys_audit_log` | **已可用**（第 4 节） | 操作人/时间/对象/结果/traceId 可追溯 |

演示口径：M1–M8 主链路已可用；告警由设备采集触发（`--section alerts` 可一键演示，
API 未注入网关令牌时 ingest 401、告警为 0）、HIS/SPD
为 `SIMULATED` 适配器，演示时应明确标注“模拟/合成”。

## 8. 演示后清理

```bash
# 停止 API / 网关 / 前端（Ctrl-C）
bash scripts/demo-local.sh stop
rm -rf data/buffer

# 重建本地演示库（清空全部演示数据；仅限 .cache/demo-pg 本地库）
# 先确认当前目录是仓库根，且没有指向任何真实数据库
rm -rf .cache/demo-pg/data .cache/demo-pg/pids
bash scripts/demo-local.sh start
```

- `seed-demo-data.sh` 每次追加业务数据；如要回到干净状态，按上面步骤重建演示库。
- `demo_admin`/`demo_self` 的初始口令只用于本地演示；改密后请记录新口令，或重建演示库。
- 脚本会创建 SYNTHETIC 审批账号 `demo_approver_1/2`（SYS_ADMIN），仅用于分级授权双人审批；
  重建演示库即删除，禁止在生产/预发复用。
- 演示截图/录屏不得包含真实患者信息；本脚本只产生 `SYNTHETIC` 数据。
- 生产/预发环境禁止执行 `seed-demo.sh` / `seed-demo-data.sh`。

## 9. 相关文档

- 数据与账号：[`docs/db/seed.md`](../db/seed.md)、[`docs/db/README.md`](../db/README.md)
- 全模块演示数据脚本：[`scripts/seed-demo-data.sh`](../../scripts/seed-demo-data.sh)
- M0 接口与权限：[`docs/modules/m0/README.md`](../modules/m0/README.md)
- 采集网关协议与指标：[`docs/gateway/README.md`](../gateway/README.md)
- 验收标准：[`docs/acceptance/ACCEPTANCE.md`](../acceptance/ACCEPTANCE.md)
