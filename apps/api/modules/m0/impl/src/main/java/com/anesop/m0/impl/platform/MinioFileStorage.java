package com.anesop.m0.impl.platform;

import com.anesop.m0.api.platform.FileStorage;

/**
 * MinIO 存储适配器占位（EXT-06）。
 *
 * <p>Docker/MinIO 当前不可用（`EXT-06`），本类只固定接口边界与切换方式：接入时实现
 * {@link FileStorage}（桶名/对象键/分片上传/预签名下载），在
 * {@code FileStorageConfiguration} 中用 {@code @ConditionalOnProperty} 选择实现，
 * 业务侧 {@code FileService} 不改。**不得**在此实现未评审的降级逻辑
 * （如把对象写到临时目录后宣称成功）。
 */
public final class MinioFileStorage implements FileStorage {

    private static final String NOT_CONFIGURED = "MinIO 存储适配器未接入（EXT-06）";

    @Override
    public String store(String sha256, byte[] content) {
        throw new UnsupportedOperationException(NOT_CONFIGURED);
    }

    @Override
    public byte[] load(String storageKey) {
        throw new UnsupportedOperationException(NOT_CONFIGURED);
    }

    @Override
    public boolean exists(String storageKey) {
        throw new UnsupportedOperationException(NOT_CONFIGURED);
    }

    @Override
    public String storageKeyFor(String sha256) {
        throw new UnsupportedOperationException(NOT_CONFIGURED);
    }
}
