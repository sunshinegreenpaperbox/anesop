package com.anesop.api.config;

import com.anesop.m0.impl.persistence.handler.UuidTypeHandler;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 持久层运行时装配（S0-07）：注册 uuid TypeHandler（全局）、乐观锁与分页插件。
 *
 * <p>jsonb/inet 由实体 {@code @TableField(typeHandler=...)} 精确绑定，不能按类型全局注册
 * （二者都是 String 处理器，会覆盖全局 String 映射）。
 */
@Configuration
public class PersistenceConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    /** MyBatis 3.5.16 未内置 UUID 处理器（m0 全部主键/外键为 uuid）。 */
    @Bean
    public ConfigurationCustomizer uuidTypeHandlerCustomizer() {
        return configuration -> configuration.getTypeHandlerRegistry()
                .register(UUID.class, UuidTypeHandler.class);
    }
}
