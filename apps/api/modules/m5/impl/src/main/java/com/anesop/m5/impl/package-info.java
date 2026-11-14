/**
 * M5 实时态势与 KPI 看板实现：只读聚合、数据范围解析、审计采样与口径阈值配置。
 *
 * <p>只依赖 m5-api（以及传递的 m0-api 审计/数据范围抽象）；不访问其他模块的 Mapper 或数据表，
 * 跨模块只读投影由 api-bootstrap 装配层适配器实现（ADR-0023）。
 */
package com.anesop.m5.impl;
