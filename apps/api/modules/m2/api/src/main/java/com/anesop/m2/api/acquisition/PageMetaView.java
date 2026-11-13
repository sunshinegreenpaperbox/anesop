package com.anesop.m2.api.acquisition;

/**
 * 分页元数据（契约 device.yaml 的 PageMeta；本域按采样帧计数）。
 *
 * @param page       当前页码，从 1 开始
 * @param size       每页采样帧数
 * @param total      符合条件的采样帧总数
 * @param totalPages 总页数；total 为 0 时为 0
 */
public record PageMetaView(int page, int size, long total, int totalPages) {
}
