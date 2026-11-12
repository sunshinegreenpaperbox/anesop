package com.anesop.m0.impl.migration;

import com.anesop.m0.impl.persistence.handler.UuidTypeHandler;
import com.anesop.m0.impl.persistence.mapper.SysAuditLogMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 测试用 MyBatis-Plus 装配：真实 PostgreSQL DataSource + 乐观锁插件 + uuid/jsonb/inet TypeHandler。
 *
 * <p>不启动 Spring 容器，直接构建 SqlSessionFactory，避免为持久层骨架引入 Web/运行时依赖；
 * 生产装配（MapperScan、Flyway、拦截器、TypeHandler 注册）仍由 S0-07 负责。
 */
final class MybatisTestSupport {

    private MybatisTestSupport() {
    }

    static SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // 只按 UUID 类型注册；jsonb/inet 由实体 @TableField(typeHandler=...) 精确绑定，
        // 不能按类注册（二者都是 String 处理器，会覆盖全局 String 映射）。
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);

        SqlSessionFactory sqlSessionFactory = factory.getObject();
        sqlSessionFactory.getConfiguration().addMapper(SysUserMapper.class);
        sqlSessionFactory.getConfiguration().addMapper(SysAuditLogMapper.class);
        return sqlSessionFactory;
    }
}
