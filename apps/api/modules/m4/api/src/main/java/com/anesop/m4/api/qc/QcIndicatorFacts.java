package com.anesop.m4.api.qc;

/**
 * 指标计算的原始计数（M4-M17-01，口径 qc-1.0.0）。
 *
 * <p>一次采集覆盖全部指标所需的计数，避免每个指标一次查询；所有计数均为**整数计数**，
 * 不含患者标识与自由文本。字段与指标定义的分子/分母一一对应：
 * <ul>
 *   <li>{@code caseCount}：区间内非取消台次（同期基数）；</li>
 *   <li>{@code recordCompletedCount}：有已结束麻醉记录单的台次；</li>
 *   <li>{@code preopCount}：有术前访视记录的台次；</li>
 *   <li>{@code threeWayCount}：完成 Time-out 与 Sign-out 的台次；</li>
 *   <li>{@code narcoticTotal}/{@code narcoticDualSigned}：麻精专册登记与双人核对完整条数；</li>
 *   <li>{@code adverseEventCount}：区间内上报的不良事件数；</li>
 *   <li>{@code chartReviewTotal}/{@code chartReviewPass}：病历抽查数与得分率 ≥90% 的记录数；</li>
 *   <li>{@code highValueOutCount}/{@code highValueUdiCount}：高值耗材出库流水与其中带 UDI 的条数。</li>
 * </ul>
 */
public record QcIndicatorFacts(
        int caseCount,
        int recordCompletedCount,
        int preopCount,
        int threeWayCount,
        int narcoticTotal,
        int narcoticDualSigned,
        int adverseEventCount,
        int chartReviewTotal,
        int chartReviewPass,
        int highValueOutCount,
        int highValueUdiCount) {

    /** 空计数（数据源异常/无数据时的安全默认，不放大指标值）。 */
    public static QcIndicatorFacts empty() {
        return new QcIndicatorFacts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
