# 演示脚本（ACCEPTANCE C：合成数据端到端）

> 对应 [`docs/acceptance/ACCEPTANCE.md`](../acceptance/ACCEPTANCE.md) C 项：初始化合成数据 →
> 登录 → 排程 → 术前 → 术中 → 复苏 → 随访 → 统计 → 质控 → 驾驶舱。
> **当前 S0（M1 前）只实现 M0 公共支撑与采集网关 POC**；排程及之后的业务步骤标注为
> 「待 M1/M2/…」，给出届时使用的契约与入口，不伪造已实现能力。
> 所有数据为 `SYNTHETIC` 合成数据，禁止使用真实患者信息。

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

## 6. 完整业务流程（M1/M2 起逐段可用）

| # | ACCEPTANCE C 步骤 | 契约/入口（已存在） | 现状 | 预期结果（落地后） |
|---|---|---|---|---|
| 2 | 排程工作台新建/调整台次、制造并解决冲突 | `packages/contracts/openapi/scheduling.yaml` | **待 M1** | 冲突返回 409 `SCHEDULE_CONFLICT`，调整后 200 |
| 3 | 术前访视 → 风险评分 → 知情同意 → 三方核查 | `anesthesia.yaml`、`patient.yaml` | **待 M2** | 访视单保存、评分回显、核查三方签名占位 |
| 4 | 术中开始记录 → 设备推送 → 预警 → 用药/事件 → 锁单 → 修订 | `anesthesia.yaml` + 采集网关 | 采集链路已可用（第 5 节）；记录单/预警/锁单**待 M2** | 记录单锁定后不可改；修订留痕、版本递增 |
| 5 | 复苏 → 转出 → 术后随访 | `anesthesia.yaml`（随访已定义） | **待 M2** | 复苏记录、转出时间、随访表单 |
| 6 | 质控指标与工作量报表 → 驾驶舱大屏 | 暂无契约（M6/M7/M8） | **待 M6/M7/M8** | 指标口径可配置、报表 <10s、大屏实时刷新 |
| 7 | 审计日志查询任意关键操作 | 表 `m0_platform.sys_audit_log` | **已可用**（第 4 节） | 操作人/时间/对象/结果/traceId 可追溯 |

演示时的诚实口径：**S0 能演示的是 M0 登录/权限/审计与采集链路；排程、麻醉记录、统计、
质控、驾驶舱尚未实现**，对应菜单/接口 404 或占位页是预期行为，不作为缺陷记录。

## 7. 演示后清理

```bash
# 停止 API / 网关 / 前端（Ctrl-C）
# 演示库如不再使用，先备份再重建；不要在生产库执行 seed-demo
# 缓冲目录（网关）
rm -rf data/buffer
```

- `demo_admin` 的初始口令只用于本地演示；改密后请记录新口令，或重建演示库。
- 演示截图/录屏不得包含真实患者信息；本脚本只产生 `SYNTHETIC` 数据。
- 生产/预发环境禁止执行 `seed-demo.sh`（脚本会拒绝疑似 prod/uat/staging 库名）。

## 8. 相关文档

- 数据与账号：[`docs/db/seed.md`](../db/seed.md)、[`docs/db/README.md`](../db/README.md)
- M0 接口与权限：[`docs/modules/m0/README.md`](../modules/m0/README.md)
- 采集网关协议与指标：[`docs/gateway/README.md`](../gateway/README.md)
- 验收标准：[`docs/acceptance/ACCEPTANCE.md`](../acceptance/ACCEPTANCE.md)
