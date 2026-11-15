package com.anesop.m0.api.platform;

/**
 * 平台能力状态冲突（409 {@code STATE_CONFLICT}，M8-M0-04）。
 *
 * <p>典型场景：同一字典类型内字典项编码重复（并发新增/更新），或并发更新冲突。
 * 文案固定，不回显冲突值。
 */
public class M0ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public M0ConflictException(String message) {
        super(message);
    }
}
