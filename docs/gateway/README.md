# 设备采集网关（S0-09 POC）

> 独立部署单元：**模拟设备 → TCP 接入 → 解析 → 标准化 → 本地文件缓冲 → 幂等入库**。
> 状态：S0-09 POC 通过（`./scripts/mvn.sh -f services/device-gateway/pom.xml verify`）。
> 设备只采集不控制；断网缓存 ≥ 4 小时；补传幂等；健康检查与指标可见。
> 依据：方案书 §3.6、`AGENTS.md` 铁律「采集解耦」、`docs/acceptance/EXTERNAL-DEPS.md` EXT-04/EXT-08。

## 1. 范围与非目标

| 项 | 本期（POC） | 非目标 / 后续 |
|---|---|---|
| 协议 | 模拟设备 TCP 行帧 v1（本文 §3） | HL7 v2/MLLP、RS-232、厂商 SDK、MQTT 全量适配（EXT-04，待 D-06 设备清单） |
| 数据 | 6 项数值体征（HR/SpO₂/NIBP×2/EtCO₂/Temp） | 波形采集与回放（方案书 §3.6.3 波形与数值分离） |
| 上行 | 默认直连 PostgreSQL（JDBC）；可选平台 ingest（§11，M3-M8-01） | RabbitMQ 上行、真实设备 mTLS |
| 控制 | 只读采集，不下发任何指令 | 设备反向控制（明确不做，方案书边界） |
| 运行时 | Java 21 + Spring Boot 3.4，原生 ServerSocket + 虚拟线程 | Netty（背压/TLS/连接数上限）、多实例分片 |

## 2. 架构

```text
 模拟设备 / 采集盒（30 间，每间 1 台）
        │  TCP 行帧（UTF-8，\n 分隔）
        ▼
┌────────────────────────────── anesop-device-gateway（独立进程）──────────────────────────────┐
│ TcpDeviceServer            FrameParser              VitalNormalizer            LocalBuffer    │
│ （ServerSocket + 虚拟线程） → （语法校验/丢弃计数） → （单位/范围/质量/时钟） → （JSONL 分段，fsync）│
│                                                                     │                        │
│                                              VitalUploader（@Scheduled，指数退避）             │
│                                                                     │ JDBC 幂等 upsert        │
│ Actuator /actuator/health、/actuator/metrics ←── Micrometer 指标 ────┘                        │
└──────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                               ▼
                            PostgreSQL 16 · schema m2_anes · anesthesia_vital
                            UNIQUE (device_id, seq, measured_at)
```

处理链不变量：

1. **接入线程不做数据库 IO**：解析→标准化→落盘即返回，数据库抖动只影响上传延迟。
2. **先落盘、后确认**：`LocalBuffer` 每行 `fsync`；`VitalUploader` 写库成功才推进 ack。
3. **幂等键永不被改写**：`measured_at` 保留设备原值（§4.2），补传/重放命中唯一键。
4. **失败可恢复**：上传失败数据留在缓冲，指数退避重试；崩溃后按 ack 位点续传。

## 3. 模拟设备协议 v1

### 3.1 帧格式

UTF-8 文本，单帧以 `\n` 结束（容忍 `\r\n`），字段以 `|` 分隔，**固定 11 段**：

```text
DEV|<deviceId>|<seq>|<measuredAt>|<hr>|<spo2>|<nibpSys>|<nibpDia>|<etco2>|<temp>|<quality>
```

| # | 字段 | 类型 / 约束 | 说明 |
|---|---|---|---|
| 1 | `DEV` | 常量 | 帧头，其他值整帧丢弃（`BAD_PREFIX`） |
| 2 | deviceId | `[A-Za-z0-9._-]{1,64}` | 设备标识；同时是缓冲目录名，禁止路径字符 |
| 3 | seq | 非负整数 | 设备侧帧序号；与 measuredAt 组成幂等键 |
| 4 | measuredAt | ISO-8601（`Z` 或偏移） | 设备测量时间；网关保留原值 |
| 5 | hr | 数值 + 可选单位 | bpm；`-` 表示本次未采到 |
| 6 | spo2 | 数值 + 可选单位 | % |
| 7 | nibpSys | 数值 + 可选单位 | mmHg，允许 `kPa` 后缀自动换算 |
| 8 | nibpDia | 数值 + 可选单位 | mmHg |
| 9 | etco2 | 数值 + 可选单位 | mmHg |
| 10 | temp | 数值 + 可选单位 | ℃，允许 `F` 后缀自动换算 |
| 11 | quality | 0/1/2 | 设备侧质量：0 正常、1 可疑、2 缺失 |

数值写法：`76`、`76bpm`、`98%`、`16.0kPa`、`98.6F`；非法写法整帧丢弃。单位换算与范围判定见 §4.1。

示例（合法）：

```text
DEV|SIM-MON-01|42|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0
DEV|SIM-MON-01|43|2026-11-12T15:59:59+08:00|76bpm|98%|16.0kPa|10.7kPa|35|98.6F|0
```

### 3.2 非法帧处理

任一不满足即**整帧丢弃、计数、继续读下一帧**（不中断连接）：字段数不符、帧头不符、设备标识非法、
序号非整数/负数、时间不可解析、数值格式非法、质量码越界、单帧超过 `max-frame-bytes`（默认 4096）。
计数指标：`anesop.gateway.frames.invalid{reason=...}`；日志只打原因码与字段名，不打报文原文。

## 4. 处理链设计

### 4.1 标准化（VitalNormalizer）

| 规则 | 行为 | 质量标记 |
|---|---|---|
| 单位 | 按指标目录换算到规范单位（kPa→mmHg ×7.50062，℉→℃）；未知单位则值置空 | `<metric>:UNIT` + 可疑 |
| 范围 | 超物理合理范围（HR 20–300、SpO₂ 50–100、NIBP 40–300/20–200、EtCO₂ 0–150、Temp 30–45） | `<metric>:RANGE` + 可疑，**保留数值** |
| 缺省 | 字段为 `-` | `<metric>:MISSING`；全部缺省时质量升级为缺失 |
| 时钟 | 偏差 ≤5s 不处理；未来时钟或 ≤120s 过去偏差 → 校正值写入 `measured_at_corrected`；>120s 视为补传/回放，校正值=原值 | `ts:FUTURE/ts:SKEW/ts:BACKFILL:<偏差毫秒>` + 可疑 |
| 质量码 | 只降不升（设备标 2 不会被"修好"） | — |

**为什么 `measured_at` 不就地校正**：它是幂等唯一键的一部分（§4.2）。若按接收时间改写，
同一帧的重放会算出新的 `measured_at` 从而插入重复行——这是 POC 实测发现的缺陷（S0-09 回归用例
`offlineFiveMinutesThenBackfillIsCompleteAndNotDuplicated`）。因此校正量**落列**
（`anesthesia_vital.measured_at_corrected`）而不是改写原值；趋势/记录单取校正值，幂等用原值。

### 4.2 本地缓冲（LocalBuffer）

- 目录：`<buffer-dir>/<deviceId>/`，段文件 `*.jsonl`，一行一条 `VitalSample`；
- 轮转：单段 ≥ 8 MiB 或存活 ≥ 10 分钟即换段，段名 `epochMillis-序号.jsonl`（字典序=时间序）；
- 持久化：逐行 `FileChannel.write` + `force(true)`（fsync）；写入成功才返回；
- 确认：`ack.json` 记录 `{segment, offset}`，写临时文件 + force + `ATOMIC_MOVE`；
  确认后删除更旧段；确认位点之前的段不再重放；
- 重启恢复：启动时按 ack 位点续传；若活动段尾部有半行（掉电），截断到最后完整行并计数
  （`buffer.corrupt.records`），半行对应帧从未确认，语义不变；
- 容量上限：单设备 64 MiB（≈66 小时 @1Hz），超限先淘汰**最旧已封段**并计数
  （`buffer.evicted.segments`，WARN 告警）；仅剩活动段仍超限则**拒收新帧**
  （`BufferCapacityException` → `buffer.rejected.records`），已落盘数据不丢。

### 4.3 幂等与重试（VitalUploader / VitalRepository）

```sql
INSERT INTO m2_anes.anesthesia_vital (...) VALUES (...)
ON CONFLICT (device_id, seq, measured_at) DO NOTHING;
```

- 每批一个事务；`executeBatch()` 影响行数 1=新插入、0=冲突；`duplicates = 批大小 − 插入数`；
- 写入成功→确认 ack→`frames.uploaded`/`frames.duplicate` 计数；失败→**不确认**，
  `frames.failed` + 指数退避（500ms 起、×2、封顶 30s，成功清零），退避窗口内调度直接返回；
- 单轮最多 64 批，避免恢复瞬间把数据库打满；暂停/恢复（`VitalUploader.pause()/resume()`）
  用于运维与断网演练，采集与落盘不受影响；
- 已知限制：无死信队列。若某批因**数据本身**（非连接）持续失败，会一直重试并告警，
  需要人工介入——见 §8 演进项。

## 5. 数据库与容量

### 5.1 表（`db/migration/V4__m2_anes_acquisition_init.sql`）

`m2_anes.anesthesia_vital`：一帧一行，体征分列，单位固定（bpm / % / mmHg / ℃）。

| 列 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | `gen_random_uuid()` |
| gateway_id / device_id | varchar(64) | 网关实例 / 设备 |
| seq | bigint | 设备序号 |
| measured_at | timestamptz | 设备测量时间原值（幂等键） |
| measured_at_corrected | timestamptz | 时钟校正后的测量时间（展示/趋势用） |
| received_at | timestamptz | 网关接收时间 |
| hr, spo2, nibp_sys, nibp_dia | smallint | 心率/血氧/收缩压/舒张压 |
| etco2, temp | numeric(5,1)/(4,1) | 呼气末二氧化碳/体温 |
| quality | smallint | 0 正常 / 1 可疑 / 2 缺失 / 3 超时 |
| quality_flags | varchar(200) | 标记明细，如 `hr:RANGE,ts:BACKFILL:-600000` |
| source | varchar(16) | AUTO / MANUAL / REPLAY |
| synthetic | boolean | POC 恒 true；真实接入由部署配置改 false |

- 唯一键 `uk_anesthesia_vital_device_seq_measured (device_id, seq, measured_at)`；
- 索引：`(device_id, measured_at DESC)`、`(measured_at)`、可疑行部分索引 `WHERE quality <> 0`；
- 约束：`quality IN (0,1,2,3)`、`source` 枚举、物理范围护栏（0–1000 等宽区间，不做临床拒绝）；
- 迁移自检：脚本末尾 `DO $$` 校验唯一键存在，缺失即迁移失败；
- expand-contract：本版本只新增 schema/表；新指标优先加可空列，行式 `metric_code` 表在 M2 需要时
  另建并双写切换；已合入脚本禁止修改（Flyway checksum）。

### 5.2 容量估算（30 间 × 1 Hz × 4 h）

| 对象 | 估算 |
|---|---|
| 帧大小（JSONL，实测） | ≈ 0.28 KB/帧（含两个时间戳） |
| 4 小时 / 间 | 14 400 帧 ≈ **3.9 MB** |
| 30 间 4 小时（缓冲下限） | 432 000 帧 ≈ **118 MB** |
| 默认单设备上限 64 MiB | ≈ 23.9 万帧 ≈ **66 小时**（≥4h 的 16 倍） |
| 数据库日增量 | 30×86 400 = **259 万行/日**，月 ≈ 7 800 万行 |
| 磁盘规划 | 缓冲卷 ≥ 20 GB（30×64 MiB≈1.9 GB 峰值 + 余量）；DB 月增 ≈ 8–12 GB（含索引） |

正式上线前必须按 `measured_at` 月分区并做历史归档（V4 头部已注明，POC 不落）。

## 6. 指标与告警

`/actuator/metrics`（micrometer，application 标签固定）：

| 指标 | 类型 | 含义 | 建议告警 |
|---|---|---|---|
| `anesop.gateway.frames.received{device}` | counter | 有效帧接收 | 某术间 1 分钟无增量 → 离线高亮 |
| `anesop.gateway.frames.invalid{reason}` | counter | 非法帧丢弃 | 5 分钟 >1% → 厂商协议变更/接线问题 |
| `anesop.gateway.frames.buffered{device}` | counter | 落盘成功 | 与 received 差值持续 >0 → 磁盘故障 |
| `anesop.gateway.frames.uploaded{device}` | counter | 入库成功 | — |
| `anesop.gateway.frames.duplicate{device}` | counter | 唯一键冲突（重复补传） | 突增 → 重放/上游重复推送 |
| `anesop.gateway.frames.failed{device}` | counter | 上传失败尝试 | >0 持续 5 分钟 → P1 |
| `anesop.gateway.buffer.pending.records{device}` | gauge | 待上传记录数 | > 10 分钟数据量（600）→ 上行拥塞 |
| `anesop.gateway.buffer.pending.records.total` | gauge | 全部设备积压 | 恢复补传观察 |
| `anesop.gateway.buffer.evicted.segments` | counter | 超限淘汰段数 | **>0 立即告警（数据丢失风险）** |
| `anesop.gateway.buffer.rejected.records` | counter | 容量拒收帧 | >0 → 扩容/排查 |
| `anesop.gateway.buffer.corrupt.records` | counter | 损坏/尾部修复 | >0 → 磁盘掉电排查 |
| `anesop.gateway.upload.connected` | gauge | 上行可用 | =0 持续 1 分钟 → P1 |
| `anesop.gateway.upload.consecutive.failures` | gauge | 连续失败次数 | ≥3 → 健康检查 DOWN |
| `anesop.gateway.upload.paused` | gauge | 人工暂停 | 非维护窗口 =1 → 检查操作记录 |

健康检查：`GET /actuator/health`（HTTP 200 = UP）。
组件 `gateway` 明细含 buffer/uploader/tcp 状态；DB 不可达或连续失败 ≥3 → DOWN（503），
人工暂停 → OUT_OF_SERVICE（503）。**本模块未引入 Prometheus registry**（依赖白名单未登记），
POC 用 `/actuator/metrics`；接 Prometheus 需先在 `docs/deps/allowlist.md` 登记
`micrometer-registry-prometheus`（Apache-2.0）。

## 7. 部署与系统要求

| 项 | POC 建议 |
|---|---|
| 运行时 | Java 21（仓库 `.toolchain/jdk21`）；单进程，`java -jar device-gateway-1.0.0-SNAPSHOT.jar` |
| 主机 | Linux x86_64，2 vCPU / 2 GB（JVM `-Xms512m -Xmx1g`）；缓冲卷 ≥20 GB SSD |
| 端口 | TCP 19090（设备接入，默认仅绑 127.0.0.1）；HTTP 8081（actuator） |
| 时间 | 必须启用 NTP/chrony；网关与设备偏差直接决定 `quality` 标记与校正 |
| 网络 | 采集 VLAN 与临床业务网逻辑隔离；设备网段到 19090 的 allowlist；POC 无 TLS/mTLS |
| 数据库 | PostgreSQL 16；网关账号仅需 `m2_anes.anesthesia_vital` 的 INSERT 权限（生产） |
| 目录 | `ANESOP_GATEWAY_BUFFER_DIR` 独立数据盘，禁止放临时目录（重启会丢） |
| 迁移 | POC 由网关自举 Flyway（`anesop.gateway.flyway-enabled=true`）；生产应关闭，由平台迁移作业独占 |

关键环境变量（其余见 `src/main/resources/application.yml`）：

```bash
ANESOP_GATEWAY_ID=GW-OR-01
ANESOP_GATEWAY_DB_URL=jdbc:postgresql://db:5432/anesop
ANESOP_GATEWAY_DB_USER=anesop_gateway
ANESOP_GATEWAY_DB_PASSWORD=***            # 由密钥管理注入，禁止入仓
ANESOP_GATEWAY_TCP_BIND=0.0.0.0
ANESOP_GATEWAY_TCP_PORT=19090
ANESOP_GATEWAY_BUFFER_DIR=/var/lib/anesop/gateway
ANESOP_GATEWAY_HTTP_PORT=8081
```

systemd 片段（生产）：

```ini
[Service]
EnvironmentFile=/etc/anesop/gateway.env
ExecStart=/usr/bin/java -Xms512m -Xmx1g -jar /opt/anesop/device-gateway.jar
Restart=always
RestartSec=5
```

冒烟：

```bash
# 1) 启动网关（需可达的 PG；测试环境可用嵌入式 PG 或本地库）
java -jar services/device-gateway/target/device-gateway-1.0.0-SNAPSHOT.jar
# 2) 推 60 帧合成数据（协议 v1）
java -cp services/device-gateway/target/device-gateway-1.0.0-SNAPSHOT.jar \
     com.anesop.gateway.simulator.SimulatedDevice 127.0.0.1 19090 SIM-MON-01 60 1000
# 3) 健康与指标
curl -s localhost:8081/actuator/health
curl -s localhost:8081/actuator/metrics/anesop.gateway.frames.received
```

## 8. 外部依赖与后续演进

- **EXT-04（真实设备协议/采集盒）**：状态 SIMULATED。本 POC 只实现模拟协议 v1；
  真实麻醉机/监护仪的协议、波特率、帧格式、采集盒选型待 D-06 设备清单与现场勘查（A-04）。
  适配原则：一设备一驱动（`DeviceDriver` 接口），新增型号只加驱动不改内核。
- **EXT-08（Docker 不可用）**：测试用 Zonky 嵌入式 PostgreSQL 16（`embedded-postgres`），
  不依赖容器；Compose/镜像相关验证标 EXT-08 待环境恢复后补。
- **Netty**：当前用原生 `ServerSocket` + 虚拟线程，连接数为 30 级足够；若接入波形或多协议
  需要背压/TLS/连接上限，再评估 Netty（BSD-3-Clause，引入前需 ADR + 白名单登记）。
- **多协议**：HL7 v2/MLLP（HAPI 2.5.1 双许可，需法务选 MPL）、RS-232、MQTT、厂商 SDK。
- **波形**：数值与波形分离，波形进对象存储/时序库（方案书 §3.6.3），本 POC 不做。
- **上行通道**：RabbitMQ/HTTPS 上报与平台侧采集 API（当前直连 PG 仅 POC 简化）。
- **可运维性**：Prometheus registry、死信队列（永久失败隔离）、补传限速分级、
  采集盒双机/掉电告警、按术间离线看板（方案书 §3.6.5）。
- **安全**：TCP 无认证（POC 仅本机/隔离 VLAN）；生产需 mTLS 或网段 allowlist + 设备白名单。

## 9. 构建与根 pom 集成

```bash
source scripts/env.sh
./scripts/mvn.sh -B -f services/device-gateway/pom.xml verify   # Checkstyle + 39 tests + SpotBugs
```

本工程为独立 Maven 工程（`parent=com.anesop:anesop-parent`），**尚未加入根 `pom.xml` 的
`<modules>`**。由主会话在集成时追加一行（位置建议：`apps/api` 之后）：

```xml
<module>services/device-gateway</module>
```

注意：加入后根构建会连带运行本模块的 Checkstyle/SpotBugs/测试；SpotBugs 使用模块级过滤
`services/device-gateway/config/spotbugs-exclude.xml`（仅排除构造器注入的 `EI_EXPOSE_REP2`，
其余全量检查，理由见文件头）。若主会话倾向把该模式提升到全局
`config/spotbugs/exclude.xml`，可删除本模块覆盖配置。

另需知会：V4 迁移合入后，`apps/api/modules/m0` 中按"迁移条数"写死的断言需要由对应任务
（S0-06/S0-07 写域）更新，本任务不改 `apps/`（见交付报告「集成注意事项」）。

## 10. 与方案书的差异（POC 显式取舍）

| 方案书 | 本 POC | 原因 |
|---|---|---|
| §3.6.3 偏差超 5s "校正" | 校正值写 `measured_at_corrected`，原值不动 | 保证幂等键稳定（§4.2） |
| §3.6.1 上行经 RabbitMQ/HTTPS | 直连 PG JDBC | POC 简化，收敛在 `VitalRepository` 一个类 |
| §3.6.3 波形入对象存储 | 不做 | 非目标，待 M2 波形迭代 |
| §3.6.1 队列满优先丢波形 | 无数值丢弃；超限先淘汰最旧段并告警 | POC 只有数值，无波形可丢 |
| 生产连接池 | `PGSimpleDataSource`（每批建连） | 避免引入未审批的 `spring-boot-starter-jdbc`/HikariCP |

## 11. 平台接入（M3-M8-01，EXT-04 SIMULATED）

POC 默认仍直连 PostgreSQL；本节是"经平台接收"的可选上行通道。平台侧契约
`packages/contracts/openapi/device.yaml 0.1.0`、表结构 `V14__m2_device_registry.sql`
与端点实现见 `docs/modules/m2/README.md` §12。

### 11.1 开关与配置

| 配置 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `anesop.gateway.platform.mode` | `ANESOP_GATEWAY_PLATFORM_MODE` | `jdbc` | `jdbc`=直连 PostgreSQL（行为不变）；`http`=平台上报 |
| `anesop.gateway.platform.url` | `ANESOP_GATEWAY_PLATFORM_URL` | 空 | 平台 API 基地址，如 `https://anesop.local/api` |
| `anesop.gateway.platform.token` | `ANESOP_GATEWAY_PLATFORM_TOKEN` | 空 | 内部网关令牌，**部署注入，禁止入仓/入日志** |
| `anesop.gateway.platform.timeout-ms` | `ANESOP_GATEWAY_PLATFORM_TIMEOUT_MS` | `3000` | 单次上报超时 |

- `mode=http` 时 url/token 必填：缺失即**启动失败**（fail-closed），不在运行期无认证上报。
- 切换只替换 `VitalSink` 实现（`GatewayConfig#vitalSink`）：解析、标准化、缓冲、退避、
  ack、健康检查与指标全部复用，采集链路不因上行方式变化。
- 平台侧要求设备已登记（`POST /v1/devices`），未登记设备 ingest 返回 404——先登记后采集。

### 11.2 认证（X-Gateway-Token）

- 上报请求头 `X-Gateway-Token` 与用户 JWT 完全分离；平台按部署注入的密钥做常量时间比较，
  **未配置密钥时一律 401**（不"未配置即放行"）。
- 令牌不写日志、不写审计、不进异常消息；网关侧异常只记录 HTTP 状态码。
- **共享令牌绑定风险（M3-M8-01 复审 S-5）**：`X-Gateway-Token` 是部署级共享密钥，**未与
  `gatewayId` 绑定**；任一持有令牌的网关可伪造其他 `gatewayId/deviceId` 上报（平台仅校验
  令牌，不校验来源网关身份）。生产前必须实现 token→gateway 映射（每网关独立密钥）或
  "单网关一密钥 + 轮换 + 吊销"，并叠加 mTLS/采集 VLAN allowlist；本期只登记，不实现。
- POC 无 mTLS；生产应叠加采集 VLAN allowlist / mTLS（§8 安全项）。

### 11.3 幂等（两层）

1. **批次层**：网关为每批生成确定性 `batchKey = <gatewayId>-<deviceId>-<首帧seq>-<末帧seq>-<首帧毫秒>`；
   平台 `device_ingest_log` 唯一键 `(gateway_id, batch_key)` 保证同一批只处理一次。
   重放返回 `duplicateBatch=true`、`acceptedRows=0`，网关照常确认（ack），不重复入库。
2. **行层**：体征明细仍由 V4 唯一键 `(device_id, seq, measured_at)` 兜底；
   新批次携带旧帧时只计 `duplicateRows`，不新增行。

因此"先入库成功、响应丢失 → 网关重试"是安全的：第二次命中批次唯一键，一次数据、一次统计。

### 11.4 质量标记与拒绝

- 网关已完成的单位换算/范围判定/时钟标记原样上报：`quality`（0 正常/1 可疑/2 缺失/3 超时）
  与 `flags`（如 `hr:RANGE`、`ts:BACKFILL:-600000`）写入 `anesthesia_vital.quality/quality_flags`；
  超范围数据**保留数值**，不用约束丢弃。
- 只有格式非法才拒绝并计入 `rejectedRows`，原因码：`SEQ_INVALID`、`MEASURED_AT_MISSING`、
  `QUALITY_OUT_OF_RANGE`、`VALUE_OUT_OF_RANGE`、`SOURCE_INVALID`、`FLAGS_TOO_LONG`；
  响应只回原因码与下标，不回显报文原文，网关日志也不打印报文。
- 平台按批次累加设备质量统计（接受/重复/拒绝/可疑/缺失），`GET /v1/devices/health`
  汇总近 N 小时质量比例（N 默认 24h、可配置 1–168，分子/分母同一窗口；`last_seen_at`
  仅在该批 `acceptedRows>0` 时推进）；网关本机缓冲深度仍看本节 §6 指标。

### 11.5 失败处理

| 平台返回 | 网关行为 |
|---|---|
| `202`（含部分拒绝） | 确认该批（ack），拒绝帧由平台计入统计并可由运维排查；链路不阻塞 |
| `401` | 不确认，指数退避重试；若持续 401 说明令牌/配置错误，健康检查 DOWN 告警（§6） |
| `404`（设备未登记） | 不确认并告警：先补登记再采集，避免未知设备写入 |
| `5xx` / 网络错误 / 空响应 | 不确认，数据留在本地缓冲，按 `upload.retry-base-ms` 指数退避 |

- 响应体缺失或无法解析同样按失败处理（**不静默 ack**）：重试由两层幂等兜底。
- 网关崩溃/重启按 `ack.json` 位点续传（§4.2）；HTTP 模式不改变本地缓冲语义（≥4h）。

### 11.6 冒烟

```bash
# 平台侧：登记设备（SYNTHETIC）
curl -s -X POST https://anesop.local/api/v1/devices \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"gatewayId":"GW-OR-01","deviceId":"SIM-MON-01","roomId":"80000000-0000-4000-8000-000000000001","deviceType":"MONITOR"}'
# 网关侧：切 http 模式后推帧，观察指标/健康
ANESOP_GATEWAY_PLATFORM_MODE=http \
ANESOP_GATEWAY_PLATFORM_URL=https://anesop.local/api \
ANESOP_GATEWAY_PLATFORM_TOKEN=*** \
java -jar services/device-gateway/target/device-gateway-1.0.0-SNAPSHOT.jar
curl -s localhost:8081/actuator/health
```

### 11.7 EXT-04 状态

真实设备协议、采集盒、mTLS 与现场联调仍属 **EXT-04（SIMULATED）**：本适配器验证的是
"网关→平台 ingest"的接口、幂等、质量与失败语义，不代表真实设备已接入；真实型号清单待
D-06 与现场勘查（A-04），接入时只新增驱动，不改本适配器。
