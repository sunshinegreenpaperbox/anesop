package com.anesop.gateway.buffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.gateway.support.GatewayTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本地缓冲边界单测（M2-M0-03）：参数校验、非法设备标识、设备目录枚举、
 * 损坏确认位点/记录跳过、已确认段清理与分页读取。
 */
class LocalBufferExtraTest {

    private static final Instant BASE = Instant.parse("2026-11-13T00:00:00Z");

    @TempDir
    Path tempDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private LocalBuffer open(long segmentBytes, long maxBytes) throws IOException {
        return LocalBuffer.open(tempDir, segmentBytes, 600_000L, maxBytes, registry);
    }

    @Test
    void rejectsInvalidArgumentsAndNonPositiveCapacity() throws IOException {
        assertThatThrownBy(() -> LocalBuffer.open(tempDir, 0L, 600_000L, 1024L, registry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("正数");
        assertThatThrownBy(() -> LocalBuffer.open(tempDir, 1024L, 0L, 1024L, registry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalBuffer.open(tempDir, 1024L, 600_000L, 0L, registry))
                .isInstanceOf(IllegalArgumentException.class);

        LocalBuffer buffer = open(1024L, 4096L);
        assertThatThrownBy(() -> buffer.append(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample");
        assertThatThrownBy(() -> buffer.peek("bad id!", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法设备标识");
        assertThat(buffer.peek("SIM-EMPTY", 0)).isEmpty();
        buffer.acknowledge("SIM-EMPTY", List.of());
        buffer.acknowledge("SIM-EMPTY", null);
        buffer.close();
    }

    @Test
    void devicesListsOnlyValidDeviceDirectories() throws IOException {
        Files.createDirectories(tempDir.resolve("SIM-A"));
        Files.createDirectories(tempDir.resolve("bad id"));
        Files.writeString(tempDir.resolve("stray-file.txt"), "x");

        LocalBuffer buffer = open(1024L, 4096L);

        assertThat(buffer.devices()).containsExactly("SIM-A");
        buffer.close();
    }

    @Test
    void devicesReturnsEmptyWhenRootDisappears() throws IOException {
        LocalBuffer buffer = open(1024L, 4096L);
        Files.delete(tempDir);

        assertThat(buffer.devices()).isEmpty();
        buffer.close();
    }

    @Test
    void skipsCorruptRecordsAndRepairsCorruptAck() throws IOException {
        LocalBuffer first = open(8_388_608L, 67_108_864L);
        first.append(GatewayTestSupport.sample("SIM-CORRUPT", 1, BASE));
        first.append(GatewayTestSupport.sample("SIM-CORRUPT", 2, BASE.plusSeconds(1)));
        first.close();

        Path deviceDir = tempDir.resolve("SIM-CORRUPT");
        Files.writeString(deviceDir.resolve("ack.json"), "{not-json");
        Path segment = singleSegment(deviceDir);
        Files.writeString(segment, "not-a-valid-sample\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        LocalBuffer reopened = open(8_388_608L, 67_108_864L);
        List<BufferedRecord> records = reopened.peek("SIM-CORRUPT", 10);

        assertThat(records).extracting(record -> record.sample().seq()).containsExactly(1L, 2L);
        assertThat(reopened.stats().corruptRecords()).isGreaterThanOrEqualTo(2L);
        reopened.close();
    }

    @Test
    void ackPointingAtDeletedSegmentResetsToOldest() throws IOException {
        LocalBuffer first = open(8_388_608L, 67_108_864L);
        first.append(GatewayTestSupport.sample("SIM-ACK", 1, BASE));
        first.close();
        Files.writeString(tempDir.resolve("SIM-ACK").resolve("ack.json"),
                "{\"segment\":\"9999999999999-000.jsonl\",\"offset\":0}");

        LocalBuffer reopened = open(8_388_608L, 67_108_864L);

        assertThat(reopened.peek("SIM-ACK", 10)).hasSize(1);
        reopened.close();
    }

    @Test
    void emptySegmentIsRepairedAndUsable() throws IOException {
        Files.createDirectories(tempDir.resolve("SIM-BLANK"));
        Files.createFile(tempDir.resolve("SIM-BLANK").resolve("0000000000001-000.jsonl"));

        LocalBuffer buffer = open(8_388_608L, 67_108_864L);
        buffer.append(GatewayTestSupport.sample("SIM-BLANK", 1, BASE));

        assertThat(buffer.peek("SIM-BLANK", 10)).hasSize(1);
        buffer.close();
    }

    @Test
    void acknowledgeResumesFromAckOffset() throws IOException {
        LocalBuffer buffer = open(8_388_608L, 67_108_864L);
        for (long seq = 1; seq <= 5; seq++) {
            buffer.append(GatewayTestSupport.sample("SIM-OFFSET", seq, BASE.plusSeconds(seq)));
        }
        List<BufferedRecord> firstTwo = buffer.peek("SIM-OFFSET", 2);
        assertThat(firstTwo).extracting(record -> record.sample().seq()).containsExactly(1L, 2L);
        buffer.acknowledge("SIM-OFFSET", firstTwo);

        assertThat(buffer.peek("SIM-OFFSET", 10))
                .extracting(record -> record.sample().seq())
                .containsExactly(3L, 4L, 5L);
        assertThat(buffer.stats().pendingRecords()).isEqualTo(3);
        buffer.close();
    }

    @Test
    void acknowledgeDeletesConsumedSegments() throws IOException {
        LocalBuffer buffer = open(1L, 67_108_864L);
        for (long seq = 1; seq <= 5; seq++) {
            buffer.append(GatewayTestSupport.sample("SIM-ROTATE", seq, BASE.plusSeconds(seq)));
        }
        List<BufferedRecord> all = buffer.peek("SIM-ROTATE", 100);
        assertThat(all).hasSize(5);

        buffer.acknowledge("SIM-ROTATE", all);

        assertThat(buffer.peek("SIM-ROTATE", 10)).isEmpty();
        assertThat(buffer.stats().pendingRecords()).isZero();
        assertThat(segments(tempDir.resolve("SIM-ROTATE"))).hasSize(1);
        buffer.close();
    }

    private static Path singleSegment(Path deviceDir) throws IOException {
        return segments(deviceDir).get(0);
    }

    private static List<Path> segments(Path deviceDir) throws IOException {
        try (Stream<Path> stream = Files.list(deviceDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList();
        }
    }
}
