package com.anesop.m0.impl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M0 文件存储配置（M8-M0-04，契约 platform.yaml 0.2.0）。
 *
 * <pre>
 * anesop:
 *   m0:
 *     file:
 *       root-dir: .cache/files
 *       max-size-bytes: 20971520
 * </pre>
 *
 * <p>约束：
 * <ul>
 *   <li>{@code root-dir}：本地 FS 适配器根目录；默认仓库内 {@code .cache/files}
 *       （已在 .gitignore 忽略），生产由部署挂载盘注入绝对路径；</li>
 *   <li>{@code max-size-bytes}：上传大小上限，默认且<b>最大</b> 20 MiB（20971520），
 *       配置大于上限时夹紧为上限（契约固定 20 MiB，不允许放宽）；小于 1 时回退默认值；</li>
 *   <li>MinIO 适配器为接口占位 EXT-06，暂不提供配置项。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "anesop.m0.file")
public class FileProperties {

    /** 契约固定上限：20 MiB。 */
    public static final long CONTRACT_MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 本地存储根目录（相对路径按进程工作目录解析；生产建议绝对挂载路径）。 */
    private String rootDir = ".cache/files";

    /** 单文件大小上限（字节，1–20971520）。 */
    private long maxSizeBytes = CONTRACT_MAX_SIZE_BYTES;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public long getMaxSizeBytes() {
        // 只允许收紧，不允许放宽到契约 20 MiB 以上。
        long configured = maxSizeBytes;
        if (configured < 1) {
            return CONTRACT_MAX_SIZE_BYTES;
        }
        return Math.min(configured, CONTRACT_MAX_SIZE_BYTES);
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }
}
