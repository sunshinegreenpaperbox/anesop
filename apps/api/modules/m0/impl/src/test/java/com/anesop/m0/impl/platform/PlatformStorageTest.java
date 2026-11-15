package com.anesop.m0.impl.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.m0.api.platform.FileStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件存储适配器测试（M8-M0-04 评审 S-5）：本地 FS 的内容寻址/路径穿越/缺失内容分支，
 * 以及 MinIO 占位适配器（EXT-06）必须显式失败、不得静默降级。
 */
class PlatformStorageTest {

    private static final String SHA = "a".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void localStorageIsContentAddressedAndIdempotent() throws IOException {
        FileStorage storage = new LocalFileStorage(tempDir);
        byte[] content = "synthetic-content".getBytes(StandardCharsets.UTF_8);

        String key = storage.store(SHA, content);

        assertThat(key).isEqualTo(SHA.substring(0, 2) + "/" + SHA.substring(2, 4) + "/" + SHA + ".bin");
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.load(key)).isEqualTo(content);
        assertThat(storage.store(SHA, content)).as("同摘要重复写入幂等").isEqualTo(key);
        assertThat(storage.storageKeyFor(SHA)).isEqualTo(key);
        assertThat(Files.walk(tempDir).filter(Files::isRegularFile).count()).isEqualTo(1);
    }

    @Test
    void localStorageRejectsInvalidKeysAndMissingContent() {
        FileStorage storage = new LocalFileStorage(tempDir);

        assertThatThrownBy(() -> storage.storageKeyFor("not-a-sha"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.storageKeyFor(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.load("../outside.bin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.load(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.load(SHA.substring(0, 2) + "/" + SHA.substring(2, 4)
                + "/" + SHA + ".bin"))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(storage.exists("../outside.bin")).isFalse();
        assertThat(storage.exists(null)).isFalse();
    }

    @Test
    void minioPlaceholderFailsClosedUntilExt06() {
        FileStorage storage = new MinioFileStorage();
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> storage.store(SHA, content))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("EXT-06");
        assertThatThrownBy(() -> storage.load("aa/bb/x.bin"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> storage.exists("aa/bb/x.bin"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> storage.storageKeyFor(SHA))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
