package com.anesop.m4.api.qc;


/**
 * 指标计算只读端口（M4-M17-01）。
 *
 * <p>为什么需要端口：指标数据来自 M2 排程台次、M3 麻醉记录单/访视/三方核查、M4 麻精专册与
 * 高值耗材流水，以及本域不良事件/病历抽查。m4-impl 不得访问其他模块的数据表或 Mapper
 * （AGENTS「模块边界与依赖禁令」），因此由 api-bootstrap 装配层实现**唯一**的跨 schema
 * 只读投影（ADR-0022），本接口只声明“要什么计数”，不暴露 SQL 与实体。
 *
 * <p>实现约束：只执行 SELECT、不写任何模块的表；数据范围条件由 {@link QcIndicatorFilter}
 * 表达，实现原样拼装、不得解释或扩大；非全院且 {@code orgIds} 为空时必须拒绝（fail-closed）。
 */
public interface QcIndicatorSource {

    /**
     * 采集一次周期内全部指标计数。
     *
     * @param filter 周期与数据范围（非空）
     * @return 计数；无数据时各计数为 0
     */
    QcIndicatorFacts collect(QcIndicatorFilter filter);

}
