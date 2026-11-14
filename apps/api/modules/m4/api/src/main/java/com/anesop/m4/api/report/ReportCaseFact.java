package com.anesop.m4.api.report;

import java.time.Instant;

/**
 * 报表统计单元的只读投影（一台次一行，M4-M16-01）。
 *
 * <p>字段只含**聚合所需的最小维度与时间**：台次/术间/科室/术式标识与麻醉记录单实际起止时间；
 * 不含患者姓名、住院号、证件号、手机号、术前诊断或 HIS 申请号（报表与导出不得含 PHI）。
 * 人员工号与姓名属院内在册人员数据（非患者标识），仅用于工作量按医师维度展示。
 *
 * <p>时间为 {@link Instant}（timestamptz 语义）；本地日期归属由服务层按
 * {@code Asia/Shanghai} 判定，投影层不做时区换算，避免同一台次在两个口径下落到不同日期。
 *
 * @param scheduleId 台次标识（仅用于去重与日志关联，不对外展示）
 * @param roomId     术间标识；可空（历史数据异常时归兜底桶）
 * @param roomNo     术间号；可空
 * @param deptId     申请科室标识；可空（未登记科室归兜底桶）
 * @param deptName   申请科室名称；可空
 * @param surgeryCode 术式编码；可空（未登记术式归 {@code UNSPECIFIED}）
 * @param surgeryName 术式名称；可空
 * @param staffId    麻醉医师档案标识；可空（无记录单/无档案归兜底桶）
 * @param staffNo    麻醉医师工号；可空
 * @param staffName  麻醉医师姓名；可空（院内在册人员，非患者标识）
 * @param startedAt  麻醉记录单开始时间；可空（无记录单或草稿）
 * @param endedAt    麻醉记录单结束时间；可空（未结束）
 */
public record ReportCaseFact(String scheduleId, String roomId, String roomNo, String deptId,
        String deptName, String surgeryCode, String surgeryName, String staffId, String staffNo,
        String staffName, Instant startedAt, Instant endedAt) {
}
