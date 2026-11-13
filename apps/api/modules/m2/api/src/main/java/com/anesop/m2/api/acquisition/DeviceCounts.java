package com.anesop.m2.api.acquisition;

/**
 * 设备数量汇总（契约 device.yaml 的 DeviceCounts）。
 *
 * @param total       登记设备总数
 * @param online      在线设备数（推导）
 * @param offline     离线设备数（推导）
 * @param maintenance 维护中设备数
 */
public record DeviceCounts(int total, int online, int offline, int maintenance) {
}
