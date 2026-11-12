package com.anesop.m1.api.application;

/**
 * 手术申请列表查询条件（契约 scheduling.yaml 0.3.0 {@code GET /v1/scheduling/applications}）。
 *
 * <p>字符串状态/紧急度由服务实现做枚举校验，非法值抛
 * {@link SurgeryApplicationValidationException}（Web 层映射 400）。
 *
 * @param page           页码（从 1 开始）
 * @param size           每页条数（1–200）
 * @param status         申请状态过滤，可空
 * @param urgency        紧急度过滤，可空
 * @param departmentName 申请科室名称过滤，可空
 */
public record SurgeryApplicationQuery(
        int page,
        int size,
        String status,
        String urgency,
        String departmentName) {
}
