package com.anesop.m4.impl.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 人员域配置装配（M4-M13-01）。
 *
 * <p>{@link StaffAuthorizationProperties} 用于“级别 → 术式/年龄/ASA 上限”的可选叠加校验；
 * 默认未配置任何级别策略，授权范围以审批人录入的显式字段为权威（【待确认】口径）。
 */
@Configuration
@EnableConfigurationProperties(StaffAuthorizationProperties.class)
public class StaffConfiguration {
}
