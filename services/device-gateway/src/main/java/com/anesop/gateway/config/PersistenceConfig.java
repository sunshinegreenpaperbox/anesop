package com.anesop.gateway.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库装配：数据源 + Flyway 自举。
 *
 * <p>POC 用 {@link PGSimpleDataSource}（每次取连接建一条 TCP），不引入连接池依赖：
 * 网关写库是单线程、1 Hz/设备的小流量，POC 足够。生产应换 HikariCP 连接池
 * （需先更新依赖白名单，见 docs/gateway/README.md §7）。
 *
 * <p>Flyway：默认 {@code anesop.gateway.flyway-enabled=true}，启动时对
 * {@code db/migration} 执行迁移，保证 {@code m2_anes.anesthesia_vital} 存在；
 * 迁移失败不阻断启动（数据继续落本地缓冲，健康检查转 DOWN），
 * 避免"数据库晚于网关就绪"时采集停摆。生产建议关闭网关自举，由平台迁移作业独占。
 *
 * <p>禁用 Spring Boot 的 Flyway 自动配置（application.yml 中
 * {@code spring.flyway.enabled=false}），避免与本类的显式迁移重复执行。
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceConfig.class);

    /**
     * 数据源。
     *
     * @param properties 网关配置
     * @return 数据源
     */
    @Bean
    public DataSource dataSource(GatewayProperties properties) {
        GatewayProperties.Database config = properties.database();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(config.url());
        dataSource.setUser(config.username());
        dataSource.setPassword(config.password());
        dataSource.setLoginTimeout(config.loginTimeoutSeconds());
        return dataSource;
    }

    /**
     * Flyway 实例（创建时按配置执行迁移）。
     *
     * @param dataSource 数据源
     * @param properties 网关配置
     * @return Flyway 实例
     */
    @Bean
    public Flyway flyway(DataSource dataSource, GatewayProperties properties) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(properties.flywayLocations())
                .load();
        if (!properties.flywayEnabled()) {
            LOG.info("Flyway 自举已关闭（anesop.gateway.flyway-enabled=false）");
            return flyway;
        }
        try {
            int applied = flyway.migrate().migrationsExecuted;
            LOG.info("Flyway 迁移完成 applied={}", applied);
        } catch (FlywayException e) {
            LOG.error("Flyway 迁移失败，网关继续运行，数据保留在本地缓冲 cause={}", e.getMessage());
        }
        return flyway;
    }
}
