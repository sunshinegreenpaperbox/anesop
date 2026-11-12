package com.anesop.api.startup;

import com.anesop.api.config.SecurityProperties;
import com.anesop.m0.impl.persistence.entity.SysUser;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动安全守卫（S0-07 追加验收 6 / S-9 / B-2.3）：默认（不依赖 profile）拒绝 {@code demo_*}
 * 演示账号与缺失/不安全 JWT 密钥。
 *
 * <p>{@code anesop.security.demo-accounts-allowed} 默认 false；只有 dev/test profile 的
 * {@code application-dev.yml} / {@code application-test.yml} 显式置 true。这样漏设
 * {@code SPRING_PROFILES_ACTIVE} 时仍按生产语义阻断，而不是悄悄加载演示种子。
 *
 * <p>演示账号只允许存在于独立 Flyway location（db/seed/demo，dev/test），一旦误挂到
 * 生产迁移路径，必须在启动阶段阻断而不是带着高权限演示账号运行。
 */
@Component
public class ProductionDemoAccountGuard implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProductionDemoAccountGuard.class);

    /** 精确匹配前缀 "demo_"：SQL LIKE 中 "_" 是通配符，必须转义为 demo\_%。 */
    private static final String DEMO_USERNAME_PATTERN = "demo\\_%";

    private final SysUserMapper sysUserMapper;
    private final boolean demoAccountsAllowed;

    public ProductionDemoAccountGuard(SysUserMapper sysUserMapper, SecurityProperties securityProperties) {
        this.sysUserMapper = sysUserMapper;
        // 只保留不可变配置值（SpotBugs EI_EXPOSE_REP2）。
        this.demoAccountsAllowed = securityProperties.isDemoAccountsAllowed();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (demoAccountsAllowed) {
            return;
        }
        Long demoAccounts = sysUserMapper.selectCount(new QueryWrapper<SysUser>()
                .apply("username LIKE {0}", DEMO_USERNAME_PATTERN));
        if (demoAccounts != null && demoAccounts > 0) {
            LOG.error("检测到 {} 个 demo_* 演示账号且 demo-accounts-allowed=false，拒绝启动", demoAccounts);
            throw new IllegalStateException(
                    "当前环境检测到 " + demoAccounts + " 个 demo_* 演示账号，拒绝启动；"
                            + "请检查 spring.flyway.locations 不得包含 db/seed/demo，并清理演示数据");
        }
    }
}
