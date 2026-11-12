package com.anesop.api.support;

import com.anesop.m0.impl.persistence.handler.UuidTypeHandler;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

/**
 * 不启动 Spring 容器的最小 MyBatis-Plus 装配（仅用于启动守卫的真实 PG 测试）。
 *
 * <p>复用生产的 {@link SysUserMapper} 与 UUID TypeHandler，确保测试覆盖真实 SQL
 * （含 {@code username LIKE 'demo\_%'} 的转义语义），而不是 mock 返回值。
 */
public final class MybatisMapperSupport {

    private MybatisMapperSupport() {
    }

    public static SqlSession openSession(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        sqlSessionFactory.getConfiguration().addMapper(SysUserMapper.class);
        return sqlSessionFactory.openSession(true);
    }
}
