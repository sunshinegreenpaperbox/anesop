package com.anesop.m4.api.report;

import com.anesop.m4.api.error.M4ValidationException;
import java.util.Locale;

/**
 * 报表统计维度白名单（契约 report.yaml 0.1.0，口径 report-1.0.0）。
 *
 * <p>{@code DEPT}=申请科室（{@code surgery_application.dept_id}）、
 * {@code STAFF}=麻醉医师（麻醉记录单 {@code created_by} → 人员档案）、
 * {@code SURGERY}=术式编码、{@code ROOM}=术间。
 *
 * <p>未匹配到的台次进入固定兜底桶（{@link #fallbackKey()}），保证各维度台次之和与总计守恒；
 * 兜底桶在排序中固定排最后，避免空值把正常科室挤到后面。
 */
public enum ReportDimension {

    /** 申请科室（{@code surgery_application.dept_id/dept_name}）。 */
    DEPT("科室", "UNASSIGNED", "未匹配科室"),

    /** 麻醉医师（麻醉记录单创建人 → {@code m4_staff.staff_profile}）。 */
    STAFF("麻醉医师", "UNASSIGNED", "未匹配医师"),

    /** 术式（{@code surgery_application.surgery_code}）。 */
    SURGERY("术式", "UNSPECIFIED", "未登记术式"),

    /** 术间（{@code m1_sched.operating_room.room_no}）。 */
    ROOM("术间", "UNASSIGNED", "未匹配术间");

    private final String label;
    private final String fallbackKey;
    private final String fallbackLabel;

    ReportDimension(String label, String fallbackKey, String fallbackLabel) {
        this.label = label;
        this.fallbackKey = fallbackKey;
        this.fallbackLabel = fallbackLabel;
    }

    /** 维度中文名（报表说明与校验文案使用）。 */
    public String label() {
        return label;
    }

    /** 未匹配维度的固定键（{@code UNASSIGNED}/{@code UNSPECIFIED}）。 */
    public String fallbackKey() {
        return fallbackKey;
    }

    /** 未匹配维度的中文兜底名。 */
    public String fallbackLabel() {
        return fallbackLabel;
    }

    /** 是否为未匹配兜底键。 */
    public boolean isFallbackKey(String key) {
        return fallbackKey.equals(key);
    }

    /**
     * 解析查询参数维度：trim + 忽略大小写，非法值 400（固定安全文案，不回显用户输入）。
     *
     * @param value 原始查询参数
     * @return 维度枚举
     */
    public static ReportDimension parse(String value) {
        return parse(value, "dimension");
    }

    /**
     * 解析维度并指定错误字段名：{@code /multi} 与 multi 导出使用复数 {@code dimensions}，
     * 保证字段级 400 与契约参数名一致（{@code /workload} 仍为 {@code dimension}）。
     *
     * @param value 原始查询参数
     * @param field 契约字段名（{@code dimension} 或 {@code dimensions}）
     * @return 维度枚举
     */
    public static ReportDimension parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        try {
            return ReportDimension.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new M4ValidationException(field,
                    field + " 必须为 DEPT/STAFF/SURGERY/ROOM");
        }
    }
}
