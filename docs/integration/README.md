# 外部系统联调（模拟器与契约测试）

> 任务：M5-M19-01（集成联调模拟器与契约测试）。依据方案书 §3.5「接口与集成方案」
> （防腐层/幂等/重试与死信）、§3.6「设备数据采集」、`AGENTS.md`「模块边界」「编码与测试要求」。
> 状态：**模拟器可联调，真实接口待接入**。全部数据为 `SYNTHETIC` 合成数据；
> 不连接真实 HIS/药房/SPD/设备，不使用真实患者数据、凭证或生产网（EXT-01/02/03/04 仍为模拟）。

## 1. 范围与 EXT 状态

| 编号 | 外部系统 | 方案书接口 | 当前实现 | 状态 | 解除条件 |
|---|---|---|---|---|---|
| EXT-01 | HIS 手术申请/患者主索引 | I-01a / I-01b | `services/simulators` HIS 模拟器（HTTP）；平台侧 `MockHisApplicationAdapter`（进程内） | 模拟器可联调，真实接口待接入 | 院方提供测试环境与接口文档（字段/值域/鉴权） |
| EXT-02 | 药房与合理用药 | I-07 | 药房模拟器（目录/库存/发药/在线状态）；平台适配器 `EXT-02 SIMULATED` | 模拟器可联调，真实接口待接入 | 药房厂商开放接口与联调窗口 |
| EXT-03 | SPD/HRP 耗材与结算 | I-08 | SPD 模拟器（目录/同步回执/在线状态）；平台适配器 `EXT-03 SIMULATED` | 模拟器可联调，真实接口待接入 | SPD 厂商接口规范与对账口径 |
| EXT-04 | 麻醉机/监护仪 | I-05 | 复用 `services/device-gateway`（TCP 协议 v1 + `SimulatedDevice`） | 模拟器可联调，真实接口待接入 | D-06 设备清单与协议（采集盒/mTLS） |

**编号口径说明（重要）**：代码适配器与 M5 任务卡沿用
`EXT-01 HIS / EXT-02 药房 / EXT-03 SPD / EXT-04 设备` 编号；`docs/acceptance/EXTERNAL-DEPS.md`
的早期登记把 EXT-02/EXT-03 用于 EMR/LIS。两套编号未统一，属既有文档遗留（不在本任务写域），
以本表与代码常量为准；`docs/acceptance/ACCEPTANCE.md` §F 已按代码编号登记联调状态。

非目标：

- 不模拟真实 CA/电子签名（EXT-05）与 EMR/LIS/PACS（I-02/I-03/I-04）。
- 不模拟生产网、真实凭证、mTLS、HL7 v2/MLLP 报文（真实接入时补齐，见 §9）。
- 不替代生产适配器：平台内 `MockHisApplicationAdapter`、`PharmacyAdapterServiceImpl`、
  `SpdAdapterServiceImpl` 仍为**进程内模拟**（无外部 I/O）。本次新增的模拟器用于**接口契约锁定**，
  真实适配器接入时按本文件字段映射与异常语义实现（契约测试可直接复用）。

## 2. 目录、构建与运行

```text
services/simulators/                 # 新增 Maven 模块（根 pom <modules> 已登记）
  pom.xml                            # 无新增第三方依赖：JDK HttpServer/HttpClient + jackson-databind
  config/simulators.json             # 默认端口与失败注入配置
  src/main/java/com/anesop/simulators/
    SimulatorsMain.java              # 进程入口（--config/--*-port/--status-file/--self-test）
    SimulatorRuntime.java            # 三服务同起 + JSON 状态
    SimulatorConfig.java             # 配置模型与校验（fail-closed）
    FailureMode.java                 # NONE / HTTP_ERROR / MALFORMED_JSON
    http/                            # 模拟 HttpServer（路由/留痕/延迟/故障注入）
    his/ pharmacy/ spd/               # 三个外部系统模拟器（SYNTHETIC 数据）
  src/test/java/...                  # 36 个单元用例（响应/错误/超时/幂等/生命周期）
scripts/simulators.sh                # start/stop/status/restart/selftest/logs/device
scripts/integration-smoke.sh         # 模拟器 + 真实 API + 独立 PG 的端到端冒烟
docs/integration/README.md           # 本文件
apps/api/api-bootstrap/src/test/java/com/anesop/api/integration/   # 契约/联调测试（test 作用域）
```

### 2.1 命令

```bash
source scripts/env.sh

# 模拟器单元测试 + 覆盖率 + 静态检查（独立模块）
./scripts/mvn.sh -B -f services/simulators/pom.xml verify

# 启动/状态/停止（输出 JSON；缺省端口 18081/18082/18083，只绑定 127.0.0.1）
bash scripts/simulators.sh start
bash scripts/simulators.sh status
bash scripts/simulators.sh stop

# 前台自检（端口 0 启动后立即关闭，验证构件与配置）
bash scripts/simulators.sh selftest

# 契约/联调测试（真实启动模拟器 HttpServer + 嵌入式 PG 16 + 真实 API）
./scripts/mvn.sh -B -f apps/api/pom.xml -pl api-bootstrap -am \
  -Dtest='HisSimulatorContractTest,PharmacySimulatorContractTest,SpdSimulatorContractTest,WireMapperContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 端到端冒烟（模拟器 + PG + API 关键流程；首次约 2–5 分钟，之后复用产物）
bash scripts/integration-smoke.sh
# 需要重建 API 产物时
bash scripts/integration-smoke.sh --rebuild
```

**单模块构建前置**：`api-bootstrap` 以 `test` 作用域依赖 `com.anesop:anesop-simulators`。
根 reactor（`./scripts/mvn.sh -B verify`）会先构建模拟器模块；只构建 `apps/api` 时须先
`./scripts/mvn.sh -B -f services/simulators/pom.xml install`（`integration-smoke.sh` 已自动处理）。

### 2.2 配置（JSON）

`services/simulators/config/simulators.json`（可用 `SIMULATORS_CONFIG` 指定其它文件）：

```json
{
  "his":      {"port": 18081, "latencyMs": 0, "failFirstRequests": 0, "errorStatus": 500, "failureMode": "NONE"},
  "pharmacy": {"port": 18082, "latencyMs": 0, "failFirstRequests": 0, "errorStatus": 500, "failureMode": "NONE"},
  "spd":      {"port": 18083, "latencyMs": 0, "failFirstRequests": 0, "errorStatus": 503, "failureMode": "NONE"}
}
```

| 字段 | 含义 | 校验 |
|---|---|---|
| `port` | 监听端口；`0` = 系统分配（测试用） | 0–65535，否则启动失败 |
| `latencyMs` | 每次请求固定延迟；大于调用方超时即为“超时”场景 | ≥ 0 |
| `failFirstRequests` | 前 N 个请求按 `errorStatus` 失败，之后恢复（验证“重试后成功”） | ≥ 0 |
| `errorStatus` | 故障注入 HTTP 状态码 | 400–599 |
| `failureMode` | `NONE` / `HTTP_ERROR`（全部请求失败）/ `MALFORMED_JSON`（200 + 非法 JSON） | 未知字段/值启动失败 |

端口也可用环境变量覆盖：`SIM_HIS_PORT`、`SIM_PHARMACY_PORT`、`SIM_SPD_PORT`。
`status`/`start` 输出 JSON（`status`、`servers[].port/baseUrl/requestCount`、`pid`），可供脚本解析。
请求留痕：`GET /sim/v1/requests`（只回环地址，记录方法/路径/请求体，不含凭据）。

## 3. HIS 模拟器（EXT-01）

### 3.1 端点

| 方法/路径 | 用途 | 主要错误码 |
|---|---|---|
| `GET /his/v1/patients/{patientNo}` | 患者主索引（I-01a） | 404 `PATIENT_NOT_FOUND`（号码格式/范围不符） |
| `GET /his/v1/surgery-applications?count=1..5&seed=` | 手术申请（I-01b） | 400 `INVALID_COUNT` / `INVALID_SEED` |
| `POST /his/v1/surgery-applications/{applicationNo}/status` | 创建结果回调（ACCEPTED/REJECTED） | 400 `INVALID_STATUS`、404 `APPLICATION_NOT_FOUND` |
| `POST /his/v1/surgery-applications/{applicationNo}/change` | 变更回调（必带 `reason`） | 400 `INVALID_CHANGE`、404 `APPLICATION_NOT_FOUND` |

确定性：同一 `(count, seed)` 返回相同申请号（导入幂等前提）；患者主索引与申请中的患者字段
由同一 `patientNo` 推导，必须一致。申请号格式 `SYNTH-HIS-APP-####`，患者号 `SYNTH-P-####`。

### 3.2 字段映射（外部 HIS → 平台）

| 外部字段 | 平台字段（`HisSurgeryApplicationRecord`） | 说明 / 值域转换 |
|---|---|---|
| `applicationNo` | `hisApplicationNo` | 幂等键（重复创建 409） |
| `patient.patientNo` | `hisPatientNo` | 患者主索引键 |
| `patient.patientName` | `patientName` | 演示文本（SYNTHETIC） |
| `patient.sex` | `gender` | `F→FEMALE`、`M→MALE`、`U→UNKNOWN`，其他值 fail-closed |
| `patient.birthday` | `birthDate` | ISO-8601 `yyyy-MM-dd` |
| `patient.age` | `age` | 整数 |
| `patient.admissionNo` | `inpatientNo` | 住院号明文**仅内存**用于脱敏/哈希，禁止入库/日志/响应 |
| `patient.department` | `deptName` | — |
| `patient.departmentCode` | `deptId` | 外部科室编码 → 平台组织标识（联调映射：`ANES` → 麻醉科演示组织） |
| `patient.ward` / `patient.bed` | `ward` / `bedNo` | — |
| `operationName` | `surgeryName` | — |
| `operationCode` | `surgeryCode` | — |
| `urgency` | `urgency` | `择期→ELECTIVE`、`限期→URGENT`、`急诊→EMERGENCY`，其他值 fail-closed |
| `plannedMinutes` | `plannedDurationMin` | 1–1440（契约） |
| `surgeon` | `surgeonName` | 演示文本 |
| `diagnosis` | `preopDiagnosis` | 演示文本 |
| `note` | `remark` | — |

契约测试另外用 `patient.departmentCode` → `UUID` 的固定映射（`ANES`→`f1000000-…0002`）与
院区 `f1000000-…0001` 断言 `deptId`/`siteId` 字段；真实接入时该映射表由院方组织主数据提供。

## 4. 药房模拟器（EXT-02）

### 4.1 端点

| 方法/路径 | 用途 | 主要错误码 |
|---|---|---|
| `GET /pharmacy/v1/drugs?keyword=` | 药品目录（编码/名称/通用名/别名模糊） | — |
| `GET /pharmacy/v1/stock/{drugCode}` | 批次库存 | 404 `DRUG_NOT_FOUND` |
| `POST /pharmacy/v1/dispense` | 发药结果 | 400 `INVALID_DISPENSE_REQUEST`、404 `DRUG_NOT_FOUND`、409 `INSUFFICIENT_STOCK`（`available`/`requested`） |
| `GET /pharmacy/v1/adapter-status` | 外部系统在线状态 | 由故障注入返回 5xx |

### 4.2 字段映射

| 外部字段 | 平台字段 | 说明 |
|---|---|---|
| `drugCode` | `CreateDrugRequest.code` | 编码唯一（重复同步 409） |
| `drugName` | `name` | 名称唯一 |
| `commonName` | `genericName` | — |
| `spec` | `specification` | — |
| `unit` | `unit` | — |
| `doseForm` | `dosageForm` | 院内剂型字典 |
| `drugClass` | `category` | 分类编码【待院方确认取值】 |
| `narcoticFlag`（0/1） | `narcotic` | 麻精标志以平台字典为权威，外部标志仅作同步来源 |
| `highRiskFlag`（0/1） | `highRisk` | — |
| `aliasList[]` | `aliases[]` | 别名全局唯一（冲突 409） |
| `online` | `PharmacyAdapterStatus.available`（`degraded = !online`） | — |
| `pendingOrders` | `pendingSyncCount` | — |
| `lastSyncAt` | `lastSyncedAt` | 从未同步为 null |
| —（适配器自身） | `adapterCode=EXT-02`、`name`、`mode=SIMULATED` | 不来自外部系统 |

发药为**无状态模拟**：不扣减库存；`quantity > 120` 返回 409（模拟库存不足）。真实接入需按处方
号幂等、库存回写与对账（本期非目标）。

## 5. SPD 模拟器（EXT-03）

### 5.1 端点

| 方法/路径 | 用途 | 主要错误码 |
|---|---|---|
| `GET /spd/v1/materials?keyword=` | 供应商耗材目录 | — |
| `POST /spd/v1/sync` | 同步（`idempotencyKey` 必填） | 400 `INVALID_SYNC_REQUEST`；故障注入 5xx |
| `GET /spd/v1/adapter-status` | 系统在线状态 | 由故障注入返回 5xx |

同步回执以 `idempotencyKey` 幂等：同键重放返回**字节级相同**回执（含相同 `syncNo`/`syncedAt`）；
新键生成新 `SPD-SYNC-####`。

### 5.2 字段映射

| 外部字段 | 平台字段 | 说明 |
|---|---|---|
| `materialCode` | `CreateMaterialRequest.code` | 唯一（重复 409） |
| `materialName` | `name` | 唯一 |
| `specification` | `specification` | — |
| `unit` | `unit` | — |
| `categoryCode` | `category` | — |
| `udiDi` | `udiDi` | 非空唯一；高值耗材追溯 |
| `highValueFlag`（0/1） | `highValue` | 高值 = 逐件 UDI 管理 |
| `expiryManagedFlag`（0/1） | `expiryManaged` | 出入库校验批次/效期 |
| `remark` | `remark` | — |
| `acknowledged` | `SpdSyncResult.success`（`degraded = !acknowledged`） | — |
| `pulled` / `pushed` | `pulledCount` / `pushedCount` | 降级时为 0 |
| `syncedAt` | `syncedAt` | — |
| `online` / `pendingReceipts` | `SpdAdapterStatus.available` / `pendingSyncCount` | `degraded = !online` |
| —（适配器自身） | `adapterCode=EXT-03`、`name`、`mode=SIMULATED` | 不来自外部系统 |

## 6. 设备模拟（EXT-04）

设备路径**不重复实现**，直接复用 `services/device-gateway`：

- 协议：TCP 行帧 v1（`DEV|deviceId|seq|measuredAt|hr|spo2|nibpSys|nibpDia|etco2|temp|quality`），
  见 `docs/gateway/README.md` §3。
- 设备端模拟器：`com.anesop.gateway.simulator.SimulatedDevice`（SYNTHETIC、无患者标识）。
- 脚本封装：`bash scripts/simulators.sh device [host] [port] [deviceId] [frames] [intervalMs]`
  （默认 `127.0.0.1 19090 SIM-MON-01 30 200`；首次自动 `mvn compile` 网关模块）。
- 既有自动化：`TcpDeviceServerTest`、`FrameParserTest`、`VitalUploader*Test`、
  `DeviceGatewayEndToEndTest`（断网缓冲/补传幂等），运行
  `./scripts/mvn.sh -B -f services/device-gateway/pom.xml verify`。
- 说明：`SimulatedDevice` 是**推帧客户端**，需配合网关监听端口；本任务不新增设备侧契约测试，
  设备字段映射（设备参数 → 内部体征模型）由网关既有测试与 `docs/gateway/README.md` 锁定。

## 7. 契约测试矩阵

| 测试类 | 断言要点 | 真实依赖 |
|---|---|---|
| `HisSimulatorContractTest` | 外部字段集合完全一致；映射到 `HisSurgeryApplicationRecord` 的 19 个字段与值域转换；患者主索引与申请一致；映射结果 `POST /v1/scheduling/applications` 201、重复 409；创建/变更回调；4xx 不重试；前 2 次 503 后成功（attempts=3）；超时降级且平台仍可用 | 真实 HisSimulator HttpServer + 嵌入式 PG 16 + 真实 API |
| `PharmacySimulatorContractTest` | 目录字段集合与映射；`POST /v1/drugs` 201/重复 409；库存/发药成功、409 不足、404；适配器状态映射满足 `drug.yaml` required 且与 API 语义一致；500 重试 3 次、超时降级为 `available=false/degraded=true/pendingSyncCount=1/lastSyncedAt=null` | 真实 PharmacySimulator + PG + API |
| `SpdSimulatorContractTest` | 目录字段集合与映射；`POST /v1/materials` 201/重复 409；同步回执幂等（同键同回执）；回执/状态映射满足 `material.yaml` required 且与 API 一致；503 重试 3 次后降级（success=false、counts=0）；超时降级 | 真实 SpdSimulator + PG + API |
| `WireMapperContractTest` | 值域转换与非法值 fail-closed；缺失/非法外部字段必须报错（不得静默空值）；降级状态语义；契约 required 字段与生成模型一致 | 纯单元（无 Spring） |
| 设备 | 走既有网关测试（见 §6） | 网关测试 |

契约测试使用的“参考适配器”语义（`ContractHttpClient`）按方案书 §3.5.1 固化：
**单次超时 + 5xx/IO 最多 3 次指数退避 + 4xx 不重试 + 重试耗尽返回失败由适配器降级**。
`FAIL_ON_UNKNOWN_PROPERTIES` 保持开启：模拟器新增/改名外部字段而映射未更新时测试先失败。

## 8. 端到端联调冒烟（`scripts/integration-smoke.sh`）

流程：启动模拟器（`scripts/simulators.sh start`）→ 启动独立 PostgreSQL 16（Zonky 嵌入式，
Docker 不可用 EXT-08）→ `java -jar api-bootstrap.jar`（`test` profile，真实 Flyway V1–V26 +
演示种子）→ 关键流程 → `trap` 清理 API/PG/模拟器。

断言（任一失败退出 1 并打印 API/PG 日志尾部）：

1. 模拟器：HIS 申请 `simulated=true`、药房目录含 `narcoticFlag`、SPD 目录含 `udiDi`、
   SPD 同 `idempotencyKey` 同回执；
2. API：`/actuator/health` = UP；`demo_admin` 登录；
3. 适配器状态：`GET /v1/drugs/pharmacy-adapter/status` → `EXT-02`；`/v1/materials/spd-adapter/status` → `EXT-03`；
4. 模拟器目录 → 平台：药房目录映射 `POST /v1/drugs`（201/重复 409）且字典可查；
   SPD 目录映射 `POST /v1/materials`（201/重复 409）且目录可查；
5. HIS 申请导入 `POST /v1/scheduling/applications/import`（`simulated=true`）与申请列表可查。

预期输出（节选）：

```text
[integration-smoke] OK 模拟器已启动 his=http://127.0.0.1:18081 ...
[integration-smoke] OK SPD 同步幂等（同 key 同回执）
[integration-smoke] OK PostgreSQL 16 已启动（jdbc:postgresql://localhost:<port>/postgres?...）
[integration-smoke] OK API health UP（http://127.0.0.1:18080，test profile）
[integration-smoke] OK 演示账号首次登录成功（demo_admin，SYNTHETIC）
[integration-smoke] OK 首次登录改密并重新登录成功（demo_admin，SYNTHETIC）
[integration-smoke] OK GET /v1/drugs/pharmacy-adapter/status → EXT-02
[integration-smoke] OK 药房目录 → POST /v1/drugs（HTTP 201）→ 字典可查
[integration-smoke] OK SPD 目录 → POST /v1/materials（HTTP 201）→ 目录可查
[integration-smoke] OK HIS 申请导入（POST /v1/scheduling/applications/import，simulated=true）
[integration-smoke] OK（模拟器 + API + 关键流程全部通过，SYNTHETIC）
```

限制与说明：

- 冒烟**不是**生产部署演练：单进程 API、单实例嵌入式 PG、无 Nginx/Redis/RabbitMQ；
- 首次运行需构建 simulator 模块与 `apps/api`（`-DskipTests`），约 2–5 分钟；产物复用后约 30–60s；
- 日志与临时数据：`tmp/integration-smoke/{api.log,postgres.log,drug-create*.json,...}`（已忽略，不入库）；
- 端口冲突时用 `SMOKE_API_PORT`/`SIM_*_PORT` 覆盖。

## 9. 与真实接口接入的差异（待确认项）

| 项 | 本期模拟口径 | 真实接入需确认 |
|---|---|---|
| 协议 | REST/JSON | HIS 可能为 HL7 v2/MLLP 或 WebService（§3.5.2），需 HAPI 解析与报文样本 |
| 鉴权 | 无（仅回环地址） | mTLS/OAuth2/院内网关、凭证托管（禁止入库） |
| 超时/重试 | 参考适配器 800ms/3 次/指数退避（测试参数） | 按院方 SLA 与接口实测时延确定（§3.5.3 I-01b <2s、I-07 <3s、I-08 <5s） |
| 字段/值域 | 本文件 §3.2/§4.2/§5.2 的模拟值域 | 以院方接口文档为准；差异只改适配器映射，不改领域模型 |
| 患者标识 | `SYNTH-P-####` / `SYNTH-HIS-APP-####` | 真实主索引/住院号（禁止入日志/审计/响应，脱敏+HMAC） |
| 对账 | 未实现（无真实数据） | HIS 申请、药房处方、SPD 消耗每日对账（§3.5.1） |
| 设备 | 模拟 TCP 帧 | D-06 设备清单、厂商协议/串口/SDK、采集盒与 mTLS |

上述差异不影响本期交付：契约测试锁定的是**字段映射与异常语义**，真实接口只需要替换
适配器中的 HTTP 客户端/报文解析，契约测试与模拟器可继续用于回归。

## 10. 证据

- 模拟器模块：`./scripts/mvn.sh -B -f services/simulators/pom.xml verify` → BUILD SUCCESS，
  36 用例，覆盖率 **97.28% 行 / 83.46% 分支**（JaCoCo 门禁 70/55）。
- 契约测试：4 个测试类（HIS/药房/SPD/映射守卫，16 用例），真实 HttpServer + 嵌入式 PG + 真实 API。
- 后端门禁：`./scripts/mvn.sh -B -f apps/api/pom.xml clean verify` → BUILD SUCCESS
  （apps/api 1147 用例，2026-11-15；api-bootstrap 271 含契约 16 与既有集成）。
- 冒烟：`bash scripts/integration-smoke.sh` → `[integration-smoke] OK（模拟器 + API + 关键流程全部通过，SYNTHETIC）`
  （实测：EXT-02/EXT-03 状态 200、药房目录创建药品 201、SPD 目录创建耗材 201、HIS 导入 simulated=true）。
- 文档：`docs/acceptance/ACCEPTANCE.md` §F、`docs/testing/README.md` §13、`docs/ai/tasks/M5-backlog.md`。

## 11. 变更记录

- 2026-11-15（M5-M19-01）：新建模拟器模块（HIS/药房/SPD，可配置延迟/超时/错误码/失败模式，
  SYNTHETIC）、`scripts/simulators.sh`、`scripts/integration-smoke.sh`、契约测试 4 类；
  设备复用 `SimulatedDevice` 并以脚本封装；EXT-01/02/03/04 状态更新为“模拟器可联调，真实接口待接入”。
