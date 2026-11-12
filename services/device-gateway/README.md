# services/device-gateway — 设备采集网关（独立部署单元）

**一句话职责**：独立进程采集麻醉机/监护仪等设备数据，完成协议解析、标准化、本地文件缓冲
与幂等补传。设备只采集不控制，网关故障不阻塞临床主流程。

独立部署、独立进程；与 `apps/api` 通过数据库表 `m2_anes.anesthesia_vital`（POC）解耦，
不承载业务规则，不对外提供业务 API。

## 目录

```text
services/device-gateway/
├── pom.xml
├── src/main/java/com/anesop/gateway/
│   ├── protocol/        # TCP 接入（ServerSocket + 虚拟线程）、帧解析
│   ├── normalize/       # 单位/范围/质量/时钟标准化
│   ├── buffer/          # JSONL 本地缓冲：fsync、段轮转、ack 位点、重启恢复
│   ├── upload/          # 定时上传、指数退避、幂等 upsert
│   ├── model/           # 领域模型（Vital 等）
│   ├── metrics/ health/ # Micrometer 指标与健康检查
│   ├── config/          # 属性绑定、PersistenceConfig（Flyway/JDBC）
│   └── simulator/       # SimulatedDevice：只产生 SYNTHETIC 数据
├── src/main/resources/application.yml
└── src/test/java/com/anesop/gateway/   # 单元 + e2e（Zonky 嵌入式 PG16）
```

协议、缓冲/幂等/重试设计、指标、容量估算与演进见
[`docs/gateway/README.md`](../../docs/gateway/README.md)。

## 如何运行

```bash
source scripts/env.sh

# 需可达的 PostgreSQL 16（V4 已建 m2_anes.anesthesia_vital）
export ANESOP_GATEWAY_DB_URL=jdbc:postgresql://127.0.0.1:5432/anesop
export ANESOP_GATEWAY_DB_USER=anesop ANESOP_GATEWAY_DB_PASSWORD=anesop

./scripts/mvn.sh -B -f services/device-gateway/pom.xml -DskipTests package
java -jar services/device-gateway/target/device-gateway-*.jar

# 健康检查与指标（HTTP 8081；TCP 接入 19090）
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8081/actuator/metrics/anesop.gateway.frames.received
```

推送合成设备帧（协议 v1，60 帧、1 秒 1 帧）：

```bash
java -cp services/device-gateway/target/device-gateway-*.jar \
  com.anesop.gateway.simulator.SimulatedDevice 127.0.0.1 19090 SIM-MON-01 60 1000
```

缓冲目录默认 `./data/buffer`（可用 `ANESOP_GATEWAY_BUFFER_DIR` 覆盖）；断网时继续落盘，
恢复后按 ack 位点补传。探活/关闭：Ctrl-C（graceful shutdown，20s 超时）。

## 如何测试

```bash
# 39 个用例：协议解析、标准化、缓冲/恢复、上传幂等、断网补传、e2e
./scripts/mvn.sh -B -f services/device-gateway/pom.xml verify

# 只跑 e2e
./scripts/mvn.sh -B -f services/device-gateway/pom.xml -Dtest=DeviceGatewayEndToEndTest test
```

- 集成测试用 Zonky 嵌入式 PostgreSQL 16（Docker 不可用，EXT-08），不依赖容器；
- 覆盖率门禁：行 ≥80%/分支 ≥70%（当前分支为 0.60 临时 ratchet，到期 2026-12-18）；
- 关键不变量必须有测试：先落盘后确认、幂等键不被改写、崩溃后按 ack 续传、
  接入线程不做数据库 IO。

## 关键契约

- **设备协议 v1**：UTF-8 行帧，`|` 分隔 11 段，字段与校验见
  [`docs/gateway/README.md`](../../docs/gateway/README.md) 第 3 节；新增设备型号只加驱动，
  不改内核。
- **落库契约**：`m2_anes.anesthesia_vital`，唯一键 `(device_id, seq, measured_at)` 是幂等键；
  `measured_at` 保留设备原值，`measured_at_corrected` 记录时钟校正；`synthetic=true` 标识
  合成数据。schema 归 M2 所有，网关只 INSERT，不建业务表。
- **边界**：只采集不控制；POC 直连 PG，正式上行通道（RabbitMQ/HTTPS）与平台侧采集 API
  待后续评估；真实设备协议/采集盒为 EXT-04（SIMULATED，待 D-06）。

## 负责人（占位）

- Owner：`@anesop/device-gateway`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`、`@anesop/architects`（跨模块/部署变更）
- 状态：S0-09 POC 完成；真实设备适配随 M2 与 D-06 设备清单推进。

## 根 pom 集成

已加入根 `pom.xml` 的 `<modules>`（`services/device-gateway`），根 reactor 的
`./scripts/mvn.sh -B verify` 会连带执行本模块的 Checkstyle/SpotBugs/测试。
