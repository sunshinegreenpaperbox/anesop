/**
 * M0 认证与权限对外接口：登录/刷新/登出、当前用户、会话与数据范围（S0-07）。
 *
 * <p>跨模块调用只允许依赖本包所在 artifact（com.anesop:m0-api），
 * 禁止依赖任何模块的 impl；由 ArchUnit 用例守住边界。
 */
package com.anesop.m0.api.security;
