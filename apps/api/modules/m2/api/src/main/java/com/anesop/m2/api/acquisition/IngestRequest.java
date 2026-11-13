package com.anesop.m2.api.acquisition;

import java.util.List;

/**
 * 网关批量上报请求（契约 device.yaml 的 IngestRequest）。
 *
 * @param batchKey 批次幂等键（1–128，[A-Za-z0-9._:-]）；补传/重试必须复用
 * @param frames   本批帧（1–500）
 */
public record IngestRequest(String batchKey, List<IngestFrame> frames) {

    /** 规范构造器：帧列表不可变。 */
    public IngestRequest {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}
