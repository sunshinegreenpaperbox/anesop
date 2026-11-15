package com.anesop.m0.api.platform;

/**
 * 文件存储适配器（M8-M0-04）：接口与实现分离，便于本地 FS → MinIO 切换不改业务代码。
 *
 * <p>当前实现：本地文件系统（{@code LocalFileStorage}，目录可配置，默认仓库内
 * {@code .cache/files}，生产挂载盘）；MinIO 适配器仅保留接口占位
 * （<b>EXT-06</b>：Docker/MinIO 当前不可用，接入时实现本接口即可）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code storageKey} 是适配器内部相对键，<b>不出接口</b>，防止绕过鉴权直接读盘；</li>
 *   <li>内容寻址：同一 SHA-256 只落盘一份；重复 {@link #store} 幂等；</li>
 *   <li>实现必须拒绝绝对路径/越界键（路径穿越），只允许在配置根目录内读写；</li>
 *   <li>不存储任何元数据：元数据统一在 {@code m0_platform.sys_file}。</li>
 * </ul>
 */
public interface FileStorage {

    /**
     * 按内容摘要存储文件内容，返回存储键（内容寻址；同摘要幂等）。
     *
     * @param sha256  文件内容 SHA-256（64 位小写十六进制）
     * @param content 文件内容
     * @return 存储键（相对根目录，不含根路径）
     */
    String store(String sha256, byte[] content);

    /**
     * 读取文件内容。
     *
     * @param storageKey 存储键（来自 {@code sys_file.storage_key}）
     * @throws java.util.NoSuchElementException 内容不存在（元数据存在但文件丢失）
     */
    byte[] load(String storageKey);

    /** 内容是否已存在（去重判定）。 */
    boolean exists(String storageKey);

    /** 由 SHA-256 推导存储键（无需访问存储）。 */
    String storageKeyFor(String sha256);
}
