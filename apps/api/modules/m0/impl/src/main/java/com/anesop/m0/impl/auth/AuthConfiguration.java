package com.anesop.m0.impl.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M0 认证基础装配：BCrypt 口令哈希与可注入时钟（令牌/锁定时间统一取时钟，便于测试）。
 */
@Configuration
public class AuthConfiguration {

    /**
     * 口令哈希：BCrypt（cost=10），与 V1 {@code password_hash} 的 {@code $2y$} 前缀兼容。
     * 禁止改为可逆加密或明文。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 认证时间源：生产为系统 UTC 时钟；单元测试可替换为固定时钟。 */
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }
}
