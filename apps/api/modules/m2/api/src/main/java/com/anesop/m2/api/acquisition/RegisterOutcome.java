package com.anesop.m2.api.acquisition;

/**
 * 设备登记结果（控制器据此选择 201 首次登记 / 200 幂等或换台绑定）。
 *
 * @param device  登记后的设备视图
 * @param created true 表示首次创建
 */
public record RegisterOutcome(DeviceView device, boolean created) {
}
