# services/device-gateway — 设备采集网关（独立部署单元）

独立进程，负责麻醉机/监护仪等设备的采集、协议解析、标准化、本地缓存与幂等补传。
与业务应用解耦：设备只采集不控制，网关故障不阻塞临床主流程。

## 状态

S0-09 POC 已落地：模拟设备 TCP 行帧 v1 → 解析 → 标准化 → 文件缓冲（fsync + 段轮转 +
ack 位点）→ JDBC 幂等入库；断网缓存 ≥4 小时、恢复补传不重复、actuator health/metrics 可见。
测试用 Zonky 嵌入式 PostgreSQL 16（Docker 不可用，EXT-08），39 个用例全绿。

- 架构、协议规范、缓冲/幂等/重试设计、指标与告警、部署要求、容量估算、后续演进：
  [`docs/gateway/README.md`](../../docs/gateway/README.md)
- 采集表迁移：`db/migration/V4__m2_anes_acquisition_init.sql`
- 构建：`./scripts/mvn.sh -B -f services/device-gateway/pom.xml verify`
- 模拟设备：`com.anesop.gateway.simulator.SimulatedDevice`（只产生 SYNTHETIC 数据）

## 边界与约束

- 断网本地缓存 ≥ 4 小时（默认 64 MiB/设备 ≈ 66 小时），恢复后按 `(device_id, seq, measured_at)`
  幂等补传，不丢不重；设备只采集不控制；
- 采集明细落在 `m2_anes.anesthesia_vital`，由 M2 模块拥有 schema；网关只 INSERT；
- 协议适配器一设备一实现（防腐层），设备字段变化不外溢；POC 只做模拟协议 v1，
  真实设备协议与采集盒待 D-06（EXT-04）；
- 不承载业务规则，不直接对外提供业务 API。

## 根 pom 集成（待主会话执行）

本工程暂未加入根 `pom.xml` 的 `<modules>`，集成时追加：

```xml
<module>services/device-gateway</module>
```
