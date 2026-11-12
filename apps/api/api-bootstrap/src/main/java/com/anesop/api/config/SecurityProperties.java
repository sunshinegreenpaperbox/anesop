package com.anesop.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全默认开关（{@code anesop.security.*}，B-2）。
 *
 * <p>安全默认（fail-closed）：{@code demo-accounts-allowed=false}，启动时若检测到
 * {@code demo_*} 演示账号即拒绝启动；仅 dev/test profile 显式置 true。不依赖 profile
 * 判断，漏设 {@code SPRING_PROFILES_ACTIVE} 时仍按生产语义守护。
 */
@ConfigurationProperties(prefix = "anesop.security")
public class SecurityProperties {

    /** 是否允许数据库中存在 {@code demo_*} 演示账号（默认 false）。 */
    private boolean demoAccountsAllowed;

    public boolean isDemoAccountsAllowed() {
        return demoAccountsAllowed;
    }

    public void setDemoAccountsAllowed(boolean demoAccountsAllowed) {
        this.demoAccountsAllowed = demoAccountsAllowed;
    }
}
