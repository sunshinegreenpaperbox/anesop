package com.anesop.m2.api.acquisition;

/**
 * 单帧拒绝原因（契约 device.yaml 的 Rejection；不回显报文原文，不含 PHI）。
 *
 * @param index  该帧在 frames 数组中的下标（从 0 起）
 * @param reason 原因码，如 SEQ_INVALID、VALUE_OUT_OF_RANGE
 */
public record Rejection(int index, String reason) {
}
