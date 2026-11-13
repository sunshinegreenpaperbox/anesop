package com.anesop.gateway.upload;

import com.anesop.gateway.model.VitalSample;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * 体征上行出口（可替换实现，M3-M8-01）。
 *
 * <p>默认实现 {@link VitalRepository}（直连 PostgreSQL，POC 简化）；配置
 * {@code anesop.gateway.platform.mode=http} 时切换为 {@link PlatformVitalReporter}
 * （平台 {@code POST /v1/devices/{gatewayId}/{deviceId}/ingest}，EXT-04 待真实接入）。
 *
 * <p>契约：成功返回<b>本次新增入库行数</b>；失败必须抛异常（不得静默丢数据），
 * 由 {@link VitalUploader} 保留缓冲、指数退避重试。两种实现都必须幂等：
 * 同一批数据重复提交不得产生重复行（JDBC 靠唯一键，HTTP 靠 batchKey + 行级唯一键）。
 */
public interface VitalSink {

    /**
     * 批量写入。
     *
     * @param samples 同一设备的样本（非空）
     * @return 实际新增行数（0 表示全部重复）
     * @throws IOException   HTTP 上行失败（网络/平台 5xx）
     * @throws SQLException  数据库不可用或约束失败
     */
    int insertAll(List<VitalSample> samples) throws IOException, SQLException;

    /**
     * 上行连通性探针（健康检查用，短超时）。
     *
     * @return true 表示可用
     */
    boolean ping();
}
