package com.anesop.m0.impl.platform;

import com.anesop.m0.api.platform.FileStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * 本地文件系统存储适配器（M8-M0-04；MinIO 为 EXT-06 占位）。
 *
 * <p>内容寻址：存储键为 {@code <sha 前 2 位>/<sha 3-4 位>/<sha>.bin}，同一摘要只落盘一份；
 * 写入先落临时文件再原子移动，避免读到半截内容；读取只允许根目录内的相对键
 * （路径穿越直接拒绝）。
 *
 * <p>目录可配置（{@code anesop.m0.file.root-dir}，默认 {@code .cache/files}；
 * 生产挂载盘），文件内容不落库、不入日志。
 */
public class LocalFileStorage implements FileStorage {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final Path root;

    public LocalFileStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String storageKeyFor(String sha256) {
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("文件摘要必须是 64 位小写十六进制");
        }
        return sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256 + ".bin";
    }

    @Override
    public String store(String sha256, byte[] content) {
        String key = storageKeyFor(sha256);
        Path target = resolve(key);
        if (Files.isRegularFile(target)) {
            // 内容寻址幂等：同一摘要已存在时不重写。
            return key;
        }
        Path parent = target.getParent();
        if (parent == null) {
            // 存储键固定含两级目录，理论上必有父目录；缺失说明键格式被破坏。
            throw new IllegalArgumentException("存储键缺少父目录");
        }
        try {
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".upload-", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return key;
        } catch (IOException e) {
            // 只记录异常类型，不记录内容与完整路径（避免把宿主目录结构写入日志）。
            throw new UncheckedIOException("文件写入失败", e);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        Path target = resolve(storageKey);
        if (!Files.isRegularFile(target)) {
            throw new NoSuchElementException("文件内容不存在");
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new UncheckedIOException("文件读取失败", e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return storageKey != null && Files.isRegularFile(resolveQuietly(storageKey));
    }

    private Path resolveQuietly(String storageKey) {
        try {
            return resolve(storageKey);
        } catch (IllegalArgumentException e) {
            return root.resolve(".invalid");
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("存储键不能为空");
        }
        Path candidate = root.resolve(storageKey).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("存储键越界");
        }
        return candidate;
    }
}
