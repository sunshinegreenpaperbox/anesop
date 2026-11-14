package com.anesop.m0.api.security;

/**
 * 科室视图（M4-M15-01 B-3：五专科室必选）。
 *
 * <p>只暴露选择科室所需的稳定字段：组织标识、编码与名称；不含地址、联系方式或人员信息。
 * 仅返回启用的 {@code org_type=DEPT} 组织，院区（SITE）与术间不在此模型中。
 *
 * @param orgId 科室组织标识
 * @param code  科室编码（院内唯一）
 * @param name  科室名称
 */
public record OrgDepartment(String orgId, String code, String name) {
}
