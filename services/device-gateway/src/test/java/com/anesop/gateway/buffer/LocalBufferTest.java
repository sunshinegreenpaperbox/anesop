package com.anesop.gateway.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.gateway.support.GatewayTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地缓冲：写入→重启恢复→按序投递、容量上限策略、崩溃尾部修复、容量估算。
 */
class LocalBufferTest {

    private static final Instant BASE = Instant.parse("2026-11-12T08:00:00Z");
    private static final long SEGMENT_BYTES = 8_388_608L;
    private static final long SEGMENT_INTERVAL_MS = 600_000L;
    private static final long MAX_BYTES = 67_108_864L;

    @TempDir
    Path tempDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void deliversInOrderAfterRestart() throws IOException {
        LocalBuffer first = LocalBuffer.open(tempDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        for (long seq = 1; seq <= 3; seq++) {
            first.append(GatewayTestSupport.sample("SIM-BUF-01", seq, BASE.plusSeconds(seq)));
        }
        List<BufferedRecord> initial = first.peek("SIM-BUF-01", 10);
        assertThat(initial).extracting(record -> record.sample().seq()).containsExactly(1L, 2L, 3L);
        first.acknowledge("SIM-BUF-01", initial.subList(0, 2));
        first.close();

        LocalBuffer reopened = LocalBuffer.open(tempDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        List<BufferedRecord> remaining = reopened.peek("SIM-BUF-01", 10);
        assertThat(remaining).extracting(record -> record.sample().seq()).containsExactly(3L);
        assertThat(reopened.stats().pendingRecords()).isEqualTo(1);

        reopened.acknowledge("SIM-BUF-01", remaining);
        assertThat(reopened.peek("SIM-BUF-01", 10)).isEmpty();
        assertThat(reopened.stats().pendingRecords()).isZero();
        reopened.close();
    }

    @Test
    void rejectsWhenActiveSegmentAloneExceedsCapacity() throws IOException {
        LocalBuffer bounded = LocalBuffer.open(tempDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, 100L, registry);

        assertThatThrownBy(() -> bounded.append(GatewayTestSupport.sample("SIM-BUF-02", 1, BASE)))
                .isInstanceOf(BufferCapacityException.class);
        assertThat(bounded.stats().rejectedRecords()).isEqualTo(1);
        assertThat(bounded.stats().pendingRecords()).isZero();
        bounded.close();
    }

    @Test
    void evictsOldestSegmentsButKeepsNewestInOrder() throws IOException {
        LocalBuffer bounded = LocalBuffer.open(tempDir, 400L, SEGMENT_INTERVAL_MS, 1500L, registry);
        for (long seq = 1; seq <= 20; seq++) {
            bounded.append(GatewayTestSupport.sample("SIM-BUF-03", seq, BASE.plusSeconds(seq)));
        }

        BufferStats stats = bounded.stats();
        assertThat(stats.evictedSegments()).isGreaterThan(0L);
        assertThat(stats.rejectedRecords()).isZero();
        assertThat(stats.bytesOnDisk()).isLessThanOrEqualTo(1500L);

        List<Long> pending = bounded.peek("SIM-BUF-03", 100).stream()
                .map(record -> record.sample().seq())
                .toList();
        assertThat(pending).isNotEmpty().isSorted();
        assertThat(pending.get(pending.size() - 1)).isEqualTo(20L);
        assertThat(pending).doesNotContain(1L);
        bounded.close();
    }

    @Test
    void repairsTruncatedTailAfterCrash() throws IOException {
        LocalBuffer first = LocalBuffer.open(tempDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        first.append(GatewayTestSupport.sample("SIM-BUF-04", 1, BASE));
        first.append(GatewayTestSupport.sample("SIM-BUF-04", 2, BASE.plusSeconds(1)));
        first.close();

        Path segment = singleSegment(tempDir.resolve("SIM-BUF-04"));
        byte[] bytes = Files.readAllBytes(segment);
        Files.write(segment, Arrays.copyOf(bytes, bytes.length - 10));

        LocalBuffer reopened = LocalBuffer.open(tempDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        // 队列是惰性打开的：先读一次触发恢复（截断尾部 + 计数），再断言
        assertThat(reopened.peek("SIM-BUF-04", 10))
                .extracting(record -> record.sample().seq())
                .containsExactly(1L);
        assertThat(reopened.stats().corruptRecords()).isGreaterThanOrEqualTo(1L);
        reopened.close();
    }

    @Test
    void defaultCapacityCoversAtLeastFourHoursAtOneHertz() throws IOException {
        LocalBuffer standard = LocalBuffer.open(tempDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        int count = 50;
        for (long seq = 1; seq <= count; seq++) {
            standard.append(GatewayTestSupport.sample("SIM-BUF-05", seq, BASE.plusSeconds(seq)));
        }
        long bytesOnDisk = standard.stats().bytesOnDisk();
        double bytesPerSample = (double) bytesOnDisk / count;
        double capacityHours = MAX_BYTES / (bytesPerSample * 3600.0d);

        assertThat(bytesPerSample).isGreaterThan(0.0d);
        assertThat(capacityHours).isGreaterThanOrEqualTo(4.0d);
        standard.close();
    }

    @Test
    void expiredSegmentsAreNotEvictedWhileUnderCapacity() throws IOException {
        LocalBuffer standard = LocalBuffer.open(tempDir, 400L, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        for (long seq = 1; seq <= 5; seq++) {
            standard.append(GatewayTestSupport.sample("SIM-BUF-06", seq, BASE.plusSeconds(seq)));
        }
        assertThat(standard.stats().evictedSegments()).isZero();
        assertThat(standard.peek("SIM-BUF-06", 100)).hasSize(5);
        standard.close();
    }

    private static Path singleSegment(Path deviceDir) throws IOException {
        try (Stream<Path> stream = Files.list(deviceDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("未找到缓冲段文件"));
        }
    }
}
