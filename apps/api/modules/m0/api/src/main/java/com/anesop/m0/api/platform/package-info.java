/**
 * M0 公共支撑平台能力（字典、消息、文件）对外接口。
 *
 * <p>本包只放接口与不可变值对象，供 api-bootstrap Web 适配层与其它模块调用；
 * 实现位于 m0-impl（{@code com.anesop.m0.impl.platform}），Web 层不得依赖 impl
 * （AGENTS 模块边界：同步只调对方 api 子模块）。
 */
package com.anesop.m0.api.platform;
