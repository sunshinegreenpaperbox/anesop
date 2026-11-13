package com.anesop.gateway.upload;

import com.anesop.gateway.model.VitalSample;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;

/**
 * 体征入库（JDBC，无 ORM）。
 *
 * <p>幂等：{@code INSERT ... ON CONFLICT (device_id, seq, measured_at) DO NOTHING}。
 * {@code executeBatch()} 返回每条语句的影响行数：1=新插入，0=唯一键冲突（重复补传）。
 * 上传器据此累加 uploaded / duplicate 指标；整个过程在一个事务内，整批要么都尝试过、
 * 要么失败回滚后保留在缓冲。
 *
 * <p>时间列用 {@code OffsetDateTime(UTC)} 绑定 timestamptz，避免 JVM 时区影响。
 */
public final class VitalRepository implements VitalSink {

    private static final String INSERT_SQL = """
            INSERT INTO m2_anes.anesthesia_vital
                (gateway_id, device_id, seq, measured_at, measured_at_corrected, received_at,
                 hr, spo2, nibp_sys, nibp_dia, etco2, temp,
                 quality, quality_flags, source, synthetic)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (device_id, seq, measured_at) DO NOTHING
            """;

    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;
    private final String gatewayId;
    private final boolean synthetic;

    /**
     * 构造仓储。
     *
     * @param dataSource 数据源
     * @param gatewayId  网关实例标识（写入每行）
     * @param synthetic  是否合成数据
     */
    public VitalRepository(DataSource dataSource, String gatewayId, boolean synthetic) {
        this.dataSource = dataSource;
        this.gatewayId = gatewayId;
        this.synthetic = synthetic;
    }

    /**
     * 批量入库。
     *
     * @param samples 体征样本（非空）
     * @return 实际新插入行数；与入参数量之差即唯一键冲突（重复）行数
     * @throws SQLException 数据库不可用或约束失败（整批回滚）
     */
    @Override
    public int insertAll(List<VitalSample> samples) throws SQLException {
        if (samples.isEmpty()) {
            return 0;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                for (VitalSample sample : samples) {
                    bind(statement, sample);
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                connection.commit();
                int inserted = 0;
                for (int count : counts) {
                    if (count > 0) {
                        inserted++;
                    }
                }
                return inserted;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /**
     * 数据库连通性探针（健康检查用，短超时）。
     *
     * @return true 表示可用
     */
    @Override
    public boolean ping() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void bind(PreparedStatement statement, VitalSample sample) throws SQLException {
        statement.setString(1, gatewayId);
        statement.setString(2, sample.deviceId());
        statement.setLong(3, sample.seq());
        statement.setObject(4, sample.measuredAt().atOffset(ZoneOffset.UTC));
        statement.setObject(5, sample.measuredAtCorrected().atOffset(ZoneOffset.UTC));
        statement.setObject(6, sample.receivedAt().atOffset(ZoneOffset.UTC));
        setSmallint(statement, 7, sample.hr());
        setSmallint(statement, 8, sample.spo2());
        setSmallint(statement, 9, sample.nibpSys());
        setSmallint(statement, 10, sample.nibpDia());
        setNumeric(statement, 11, sample.etco2());
        setNumeric(statement, 12, sample.temp());
        statement.setInt(13, sample.quality());
        String flags = sample.qualityFlagsText();
        statement.setString(14, flags.isEmpty() ? null : flags);
        statement.setString(15, sample.source());
        statement.setBoolean(16, synthetic);
    }

    private static void setSmallint(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.SMALLINT);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNumeric(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }
}
