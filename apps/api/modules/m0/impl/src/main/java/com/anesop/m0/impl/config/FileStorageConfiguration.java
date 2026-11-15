package com.anesop.m0.impl.config;

import com.anesop.m0.api.platform.FileStorage;
import com.anesop.m0.impl.platform.LocalFileStorage;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M0 文件存储装配（M8-M0-04）：只装配本地 FS 适配器与配置。
 *
 * <p>MinIO 适配器（EXT-06）实现同一 {@link FileStorage} 接口，接入时新增一个
 * {@code @ConditionalOnProperty} 的 Bean 并切换配置，不修改 {@code FileService}。
 */
@Configuration
@EnableConfigurationProperties(FileProperties.class)
public class FileStorageConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(FileStorageConfiguration.class);

    /** 本地文件系统适配器：根目录可配置，默认仓库内 .cache/files（已 gitignore）。 */
    @Bean
    public FileStorage localFileStorage(FileProperties properties) {
        Path root = Path.of(properties.getRootDir()).toAbsolutePath().normalize();
        LOG.info("M0 文件存储适配器：LOCAL_FS，根目录={}，单文件上限={} 字节",
                root, properties.getMaxSizeBytes());
        return new LocalFileStorage(root);
    }
}
