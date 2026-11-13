package com.anesop.gateway.support;

import com.anesop.gateway.model.VitalSample;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * 测试公共支撑：嵌入式 PostgreSQL 16（Docker 不可用，EXT-08）、仓库根定位、
 * 合成数据工厂与轮询断言。
 *
 * <p>同一 Surefire JVM 内只启动一次 PG；测试类之间通过 TRUNCATE 隔离数据。
 */
public final class GatewayTestSupport {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private GatewayTestSupport() {
    }

    /**
     * 嵌入式 PG 数据源（库 postgres，用户 postgres）。
     *
     * @return 数据源
     */
    public static DataSource dataSource() {
        return POSTGRES.getPostgresDatabase();
    }

    /**
     * JDBC URL（供 Spring 配置使用）。
     *
     * @return URL
     */
    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl("postgres", "postgres");
    }

    /**
     * 仓库根（含 db/migration 的目录）。
     *
     * @return 绝对路径
     */
    public static Path repoRoot() {
        String configured = System.getProperty("anesop.root");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate.resolve("db/migration"))) {
                return candidate;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("db/migration"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到含 db/migration 的仓库根目录");
    }

    /**
     * 生产迁移路径（只含 db/migration）。
     *
     * @return Flyway locations 字符串
     */
    public static String migrationLocations() {
        return "filesystem:" + repoRoot().resolve("db/migration");
    }

    /**
     * 构造 Flyway。
     *
     * @param dataSource 数据源
     * @return Flyway
     */
    public static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations())
                .load();
    }

    /**
     * 清空采集表，保证用例间隔离。
     *
     * @param dataSource 数据源
     * @throws SQLException 执行失败
     */
    public static void truncateVital(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE m2_anes.anesthesia_vital");
        }
    }

    /**
     * 构造一条合成体征样本（无任何患者标识）。
     *
     * @param deviceId   设备标识
     * @param seq        帧序号
     * @param measuredAt 测量时间
     * @return 样本
     */
    public static VitalSample sample(String deviceId, long seq, Instant measuredAt) {
        return new VitalSample(deviceId, seq, measuredAt, measuredAt, Instant.now(),
                76, 98, 120, 80, new BigDecimal("35.0"), new BigDecimal("36.7"),
                0, List.of(), "AUTO");
    }

    /**
     * 轮询等待条件成立，超时抛 AssertionError（真实失败，不会静默通过）。
     *
     * <p>这是"显式条件等待"而非固定睡眠：只用于无法注入时钟的外部异步边界
     * （真实 socket、调度线程）。断言依赖的是条件本身，轮询间隔只影响发现延迟；
     * 超时按并发负载留足余量（本模块 socket 用例 ≥10s，端到端 ≥60s）。
     *
     * @param description 条件描述
     * @param timeout     超时
     * @param condition   条件
     */
    public static void awaitTrue(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50L);
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("等待超时：" + description);
        }
    }

    /**
     * 轮询间隔（仅供 {@link #awaitTrue} 使用）。
     *
     * <p>禁止把它当作同步手段：新用例不得用固定 sleep 赌时序，可确定化的场景
     * 应注入时钟或等待显式条件（docs/testing/README.md §8）。
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", e);
        }
    }

    /**
     * 生成协议 v1 行（与 SimulatedDevice 同源的合成数据）。
     *
     * @param deviceId   设备标识
     * @param seq        序号
     * @param measuredAt 测量时间
     * @return 行文本（不含换行）
     */
    public static String frame(String deviceId, long seq, Instant measuredAt) {
        return "DEV|" + deviceId + "|" + seq + "|" + measuredAt + "|76|98|120|80|35.0|36.7|0";
    }

    /**
     * 生成连续 N 帧（测量时间按秒递增）。
     *
     * @param deviceId 设备标识
     * @param fromSeq  起始序号（含）
     * @param toSeq    结束序号（含）
     * @param base     第一帧测量时间
     * @return 行列表
     */
    public static List<String> frames(String deviceId, long fromSeq, long toSeq, Instant base) {
        List<String> lines = new ArrayList<>();
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            lines.add(frame(deviceId, seq, base.plusSeconds(seq - fromSeq)));
        }
        return lines;
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("嵌入式 PostgreSQL 16 启动失败", e);
        }
    }
}
