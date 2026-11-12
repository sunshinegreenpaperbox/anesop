package com.anesop.gateway.buffer;

import com.anesop.gateway.metrics.GatewayMetricNames;
import com.anesop.gateway.model.VitalSample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件缓冲：断网不丢数据的第一道保障（方案书 §3.6.1 缓存段、§3.6.4 离线）。
 *
 * <p>存储模型：每设备一个目录，目录内按时间/大小轮转的 JSONL 段文件（一行一条体征样本）。
 * 每写一行即 {@code force(true)}（fsync）；ack 位点写在设备目录的 {@code ack.json}，
 * 采用"写临时文件 + force + 原子重命名"，因此掉电重启后可以从最后确认位点继续投递，
 * 已确认段在下次确认时删除。
 *
 * <p>容量策略：
 * <ol>
 *   <li>单设备磁盘上限 {@code maxBytesPerDevice}，默认 64 MiB；按 1 Hz 实测约 0.28 KB/条，
 *       可存约 66 小时，远大于方案书要求的 4 小时（见 docs/gateway/README.md §5 容量估算）。</li>
 *   <li>超限先淘汰<b>最旧已封段</b>（活动段永不淘汰），并计数告警；</li>
 *   <li>若只剩活动段仍超限，则<b>拒收</b>新帧（抛 {@link BufferCapacityException}），
 *       由调用方计数，已落盘数据不丢。</li>
 * </ol>
 *
 * <p>崩溃一致性：启动时若活动段尾部存在未写完的半行（无换行结尾），截断到最后完整行
 * 并计数；半行对应的那一帧本就没有确认，重放语义不变。
 *
 * <p>线程模型：同一设备队列内串行（synchronized），不同设备目录互不影响；
 * 设备数量为 30 级，锁竞争可忽略。
 */
public final class LocalBuffer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalBuffer.class);

    /** 设备目录名与协议设备标识保持同一字符集，避免路径穿越。 */
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static final String ACK_FILE = "ack.json";
    private static final String SEGMENT_SUFFIX = ".jsonl";
    private static final int READ_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final int BACKWARD_SCAN_CHUNK = 8192;

    private final Path root;
    private final long segmentBytes;
    private final long segmentIntervalMs;
    private final long maxBytesPerDevice;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, DeviceQueue> queues = new ConcurrentHashMap<>();
    private final AtomicLong evictedSegments = new AtomicLong();
    private final AtomicLong rejectedRecords = new AtomicLong();
    private final AtomicLong corruptRecords = new AtomicLong();

    private LocalBuffer(Path root, long segmentBytes, long segmentIntervalMs, long maxBytesPerDevice,
            MeterRegistry meterRegistry) throws IOException {
        if (segmentBytes <= 0 || segmentIntervalMs <= 0 || maxBytesPerDevice <= 0) {
            throw new IllegalArgumentException("缓冲容量参数必须为正数");
        }
        this.root = root.toAbsolutePath().normalize();
        this.segmentBytes = segmentBytes;
        this.segmentIntervalMs = segmentIntervalMs;
        this.maxBytesPerDevice = maxBytesPerDevice;
        this.meterRegistry = meterRegistry;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Files.createDirectories(this.root);
        FunctionCounter.builder(GatewayMetricNames.BUFFER_EVICTED_SEGMENTS, evictedSegments, AtomicLong::doubleValue)
                .register(meterRegistry);
        FunctionCounter.builder(GatewayMetricNames.BUFFER_REJECTED_RECORDS, rejectedRecords, AtomicLong::doubleValue)
                .register(meterRegistry);
        FunctionCounter.builder(GatewayMetricNames.BUFFER_CORRUPT_RECORDS, corruptRecords, AtomicLong::doubleValue)
                .register(meterRegistry);
        Gauge.builder(GatewayMetricNames.BUFFER_PENDING_TOTAL, this, LocalBuffer::pendingRecords)
                .register(meterRegistry);
    }

    /**
     * 打开（或恢复）缓冲目录。
     *
     * @param root              缓冲根目录
     * @param segmentBytes      单段字节上限
     * @param segmentIntervalMs 单段存活时间上限（毫秒）
     * @param maxBytesPerDevice 单设备磁盘上限（字节）
     * @param meterRegistry     指标注册表
     * @return 缓冲实例
     * @throws IOException 目录不可创建
     */
    public static LocalBuffer open(Path root, long segmentBytes, long segmentIntervalMs, long maxBytesPerDevice,
            MeterRegistry meterRegistry) throws IOException {
        return new LocalBuffer(root, segmentBytes, segmentIntervalMs, maxBytesPerDevice, meterRegistry);
    }

    /**
     * 追加一条样本（落盘并 fsync 后才返回）。
     *
     * @param sample 体征样本
     * @throws IOException 写入失败或容量拒收（{@link BufferCapacityException}）
     */
    public void append(VitalSample sample) throws IOException {
        if (sample == null) {
            throw new IllegalArgumentException("sample 不能为空");
        }
        queueFor(sample.deviceId()).append(sample);
    }

    /**
     * 列出磁盘上存在数据的设备（含重启前遗留目录）。
     *
     * @return 设备标识列表（字典序）
     * @throws IOException 目录不可读
     */
    public List<String> devices() throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> DEVICE_ID.matcher(name).matches())
                    .sorted()
                    .toList();
        }
    }

    /**
     * 读取某设备下一批待上传记录（不改变确认位点）。
     *
     * @param deviceId 设备标识
     * @param max      单批上限
     * @return 按写入顺序排列的记录；无数据为空列表
     * @throws IOException 读取失败
     */
    public List<BufferedRecord> peek(String deviceId, int max) throws IOException {
        if (max <= 0) {
            return List.of();
        }
        return queueFor(deviceId).peek(max);
    }

    /**
     * 确认一批记录已入库（写 ack 位点并清理已消费段）。
     *
     * @param deviceId 设备标识
     * @param records  已成功入库的记录（按读取顺序）
     * @throws IOException 位点写入失败
     */
    public void acknowledge(String deviceId, List<BufferedRecord> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        queueFor(deviceId).acknowledge(records);
    }

    /**
     * 运行态快照（只统计已打开的设备队列）。
     *
     * @return 快照
     */
    public BufferStats stats() {
        long pending = 0;
        long bytes = 0;
        for (DeviceQueue queue : queues.values()) {
            pending += queue.pendingRecords();
            bytes += queue.bytesOnDisk();
        }
        return new BufferStats(pending, bytes, queues.size(),
                evictedSegments.get(), rejectedRecords.get(), corruptRecords.get());
    }

    /**
     * 已打开队列的待上传记录总数（供聚合 gauge 使用）。
     *
     * @return 记录数
     */
    public long pendingRecords() {
        long total = 0;
        for (DeviceQueue queue : queues.values()) {
            total += queue.pendingRecords();
        }
        return total;
    }

    @Override
    public void close() {
        for (DeviceQueue queue : queues.values()) {
            queue.closeQuietly();
        }
        queues.clear();
    }

    private DeviceQueue queueFor(String deviceId) throws IOException {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("非法设备标识");
        }
        DeviceQueue existing = queues.get(deviceId);
        if (existing != null) {
            return existing;
        }
        try {
            return queues.computeIfAbsent(deviceId, id -> {
                try {
                    DeviceQueue created = new DeviceQueue(id);
                    Gauge.builder(GatewayMetricNames.BUFFER_PENDING_RECORDS, created,
                                    queue -> queue.pendingRecords())
                            .tag("device", id)
                            .register(meterRegistry);
                    return created;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** 单设备队列：段管理、位点、计数。 */
    private final class DeviceQueue {

        private final String deviceId;
        private final Path dir;
        private String activeSegment;
        private FileChannel activeChannel;
        private long activeCreatedAtMillis;
        private String ackSegment;
        private long ackOffset;
        private long bytesOnDisk;
        private long pendingRecords;
        private long segmentCounter;

        DeviceQueue(String deviceId) throws IOException {
            this.deviceId = deviceId;
            this.dir = root.resolve(deviceId);
            Files.createDirectories(dir);
            readAck();
            List<String> segments = listSegments();
            if (segments.isEmpty()) {
                openNewSegment();
            } else {
                String last = segments.get(segments.size() - 1);
                repairTail(dir.resolve(last));
                List<String> repaired = listSegments();
                if (repaired.isEmpty()) {
                    openNewSegment();
                } else {
                    activeSegment = repaired.get(repaired.size() - 1);
                    activeCreatedAtMillis = Files.getLastModifiedTime(dir.resolve(activeSegment)).toMillis();
                    activeChannel = FileChannel.open(dir.resolve(activeSegment),
                            StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                }
            }
            List<String> current = listSegments();
            bytesOnDisk = computeBytes(current);
            pendingRecords = countPending(current);
            if (ackSegment != null && !current.contains(ackSegment)) {
                ackSegment = null;
                ackOffset = 0L;
            }
        }

        void append(VitalSample sample) throws IOException {
            synchronized (this) {
                byte[] line;
                try {
                    line = (objectMapper.writeValueAsString(sample) + "\n").getBytes(StandardCharsets.UTF_8);
                } catch (JsonProcessingException e) {
                    throw new IOException("缓冲序列化失败", e);
                }
                if (bytesOnDisk + line.length > maxBytesPerDevice) {
                    evictSealedUntilFits(line.length);
                }
                if (bytesOnDisk + line.length > maxBytesPerDevice) {
                    rejectedRecords.incrementAndGet();
                    throw new BufferCapacityException(
                            "设备缓冲达到上限且无可淘汰段 device=" + deviceId + " limit=" + maxBytesPerDevice);
                }
                rotateIfNeeded();
                activeChannel.write(ByteBuffer.wrap(line));
                activeChannel.force(true);
                bytesOnDisk += line.length;
                pendingRecords++;
            }
        }

        List<BufferedRecord> peek(int max) throws IOException {
            synchronized (this) {
                List<String> segments = listSegments();
                if (segments.isEmpty()) {
                    return List.of();
                }
                long[] start = startPosition(segments);
                int startIndex = (int) start[0];
                long startOffset = start[1];
                List<BufferedRecord> records = new ArrayList<>();
                for (int i = startIndex; i < segments.size() && records.size() < max; i++) {
                    long offset = i == startIndex ? startOffset : 0L;
                    readSegment(segments.get(i), offset, max - records.size(), records);
                }
                return List.copyOf(records);
            }
        }

        void acknowledge(List<BufferedRecord> records) throws IOException {
            synchronized (this) {
                BufferedRecord last = records.get(records.size() - 1);
                ackSegment = last.segment();
                ackOffset = last.endOffset();
                writeAck();
                pendingRecords = Math.max(0L, pendingRecords - records.size());
                deleteConsumedSegments();
            }
        }

        long pendingRecords() {
            synchronized (this) {
                return pendingRecords;
            }
        }

        long bytesOnDisk() {
            synchronized (this) {
                return bytesOnDisk;
            }
        }

        void closeQuietly() {
            synchronized (this) {
                if (activeChannel == null) {
                    return;
                }
                try {
                    activeChannel.force(true);
                    activeChannel.close();
                } catch (IOException e) {
                    LOG.warn("关闭缓冲段失败 device={} cause={}", deviceId, e.getMessage());
                }
            }
        }

        private synchronized void evictSealedUntilFits(long incomingBytes) throws IOException {
            for (String segment : listSegments()) {
                if (bytesOnDisk + incomingBytes <= maxBytesPerDevice) {
                    return;
                }
                if (segment.equals(activeSegment)) {
                    return;
                }
                deleteSegment(segment);
                evictedSegments.incrementAndGet();
                LOG.warn("缓冲超限，淘汰最旧段 device={} segment={} limit={}", deviceId, segment, maxBytesPerDevice);
            }
        }

        private synchronized void deleteSegment(String segment) throws IOException {
            Path path = dir.resolve(segment);
            long size = Files.exists(path) ? Files.size(path) : 0L;
            if (Files.deleteIfExists(path)) {
                bytesOnDisk = Math.max(0L, bytesOnDisk - size);
                LOG.info("删除已消费缓冲段 device={} segment={} bytes={}", deviceId, segment, size);
            }
            if (segment.equals(ackSegment)) {
                ackSegment = null;
                ackOffset = 0L;
                writeAck();
            }
            pendingRecords = Math.max(0L, countPending(listSegments()));
        }

        private synchronized void deleteConsumedSegments() throws IOException {
            List<String> segments = listSegments();
            int ackIndex = ackSegment == null ? -1 : segments.indexOf(ackSegment);
            if (ackIndex < 0) {
                return;
            }
            for (int i = 0; i < ackIndex; i++) {
                deleteSegment(segments.get(i));
            }
            List<String> remaining = listSegments();
            if (ackSegment == null || ackSegment.equals(activeSegment) || !remaining.contains(ackSegment)) {
                return;
            }
            if (ackOffset >= Files.size(dir.resolve(ackSegment))) {
                deleteSegment(ackSegment);
                List<String> afterDelete = listSegments();
                ackSegment = afterDelete.isEmpty() ? null : afterDelete.get(0);
                ackOffset = 0L;
                writeAck();
            }
        }

        private synchronized void rotateIfNeeded() throws IOException {
            long age = System.currentTimeMillis() - activeCreatedAtMillis;
            if (activeChannel.size() >= segmentBytes || age >= segmentIntervalMs) {
                activeChannel.force(true);
                activeChannel.close();
                openNewSegment();
            }
        }

        private synchronized void openNewSegment() throws IOException {
            Path path = null;
            while (path == null) {
                long now = System.currentTimeMillis();
                String candidate = String.format(Locale.ROOT, "%013d-%03d%s",
                        now, segmentCounter++ % 1000, SEGMENT_SUFFIX);
                Path candidatePath = dir.resolve(candidate);
                if (!Files.exists(candidatePath)) {
                    Files.createFile(candidatePath);
                    path = candidatePath;
                    activeSegment = candidate;
                }
            }
            activeCreatedAtMillis = System.currentTimeMillis();
            activeChannel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        }

        private synchronized void readSegment(String segment, long fromOffset, int limit, List<BufferedRecord> out)
                throws IOException {
            Path path = dir.resolve(segment);
            if (!Files.exists(path)) {
                return;
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                long size = channel.size();
                if (fromOffset >= size) {
                    return;
                }
                channel.position(fromOffset);
                ByteBuffer buffer = ByteBuffer.allocate(READ_CHUNK_BYTES);
                ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
                long position = fromOffset;
                long lineStart = fromOffset;
                boolean stop = false;
                while (!stop && out.size() < limit) {
                    buffer.clear();
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    for (int i = 0; i < read; i++) {
                        byte current = buffer.get(i);
                        position++;
                        if (current == '\n') {
                            addRecord(segment, lineStart, position, lineBuffer.toByteArray(), out);
                            lineBuffer.reset();
                            lineStart = position;
                            if (out.size() >= limit) {
                                stop = true;
                                break;
                            }
                        } else if (lineBuffer.size() < MAX_LINE_BYTES) {
                            lineBuffer.write(current);
                        }
                    }
                }
            }
        }

        private synchronized void addRecord(String segment, long lineStart, long endOffset, byte[] bytes,
                List<BufferedRecord> out) {
            if (bytes.length == 0) {
                return;
            }
            try {
                out.add(new BufferedRecord(segment, endOffset, objectMapper.readValue(bytes, VitalSample.class)));
            } catch (IOException e) {
                corruptRecords.incrementAndGet();
                LOG.warn("缓冲记录损坏，跳过 device={} segment={} offset={}", deviceId, segment, lineStart);
            }
        }

        private synchronized long[] startPosition(List<String> segments) {
            if (ackSegment != null) {
                int index = segments.indexOf(ackSegment);
                if (index >= 0) {
                    return new long[] {index, ackOffset};
                }
            }
            return new long[] {0L, 0L};
        }

        private synchronized long countPending(List<String> segments) throws IOException {
            long[] start = startPosition(segments);
            long count = 0;
            for (int i = (int) start[0]; i < segments.size(); i++) {
                long offset = i == (int) start[0] ? start[1] : 0L;
                count += countLines(segments.get(i), offset);
            }
            return count;
        }

        private synchronized long countLines(String segment, long fromOffset) throws IOException {
            Path path = dir.resolve(segment);
            if (!Files.exists(path)) {
                return 0L;
            }
            long count = 0;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                long size = channel.size();
                if (fromOffset >= size) {
                    return 0L;
                }
                channel.position(fromOffset);
                ByteBuffer buffer = ByteBuffer.allocate(READ_CHUNK_BYTES);
                while (true) {
                    buffer.clear();
                    int read = channel.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    for (int i = 0; i < read; i++) {
                        if (buffer.get(i) == '\n') {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        private synchronized long computeBytes(List<String> segments) throws IOException {
            long total = 0;
            for (String segment : segments) {
                Path path = dir.resolve(segment);
                if (Files.exists(path)) {
                    total += Files.size(path);
                }
            }
            return total;
        }

        private synchronized List<String> listSegments() throws IOException {
            if (!Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> stream = Files.list(dir)) {
                return stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(SEGMENT_SUFFIX))
                        .sorted()
                        .toList();
            }
        }

        private synchronized void repairTail(Path path) throws IOException {
            long size = Files.size(path);
            if (size == 0) {
                return;
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer last = ByteBuffer.allocate(1);
                channel.position(size - 1);
                int read = channel.read(last);
                if (read != 1 || last.get(0) == '\n') {
                    return;
                }
                long truncateAt = lastNewlineOffset(channel, size);
                channel.truncate(truncateAt);
                corruptRecords.incrementAndGet();
                LOG.warn("缓冲段尾部有未完成写入，已截断 device={} segment={} size={} truncatedTo={}",
                        deviceId, path.getFileName(), size, truncateAt);
            }
        }

        private synchronized long lastNewlineOffset(FileChannel channel, long size) throws IOException {
            long position = size;
            ByteBuffer buffer = ByteBuffer.allocate(BACKWARD_SCAN_CHUNK);
            while (position > 0) {
                int chunk = (int) Math.min(buffer.capacity(), position);
                position -= chunk;
                buffer.clear();
                buffer.limit(chunk);
                channel.position(position);
                int read = channel.read(buffer);
                if (read <= 0) {
                    break;
                }
                for (int i = read - 1; i >= 0; i--) {
                    if (buffer.get(i) == '\n') {
                        return position + i + 1;
                    }
                }
            }
            return 0L;
        }

        private synchronized void readAck() {
            Path path = dir.resolve(ACK_FILE);
            if (!Files.exists(path)) {
                ackSegment = null;
                ackOffset = 0L;
                return;
            }
            try {
                AckState state = objectMapper.readValue(Files.readAllBytes(path), AckState.class);
                ackSegment = state.segment();
                ackOffset = Math.max(0L, state.offset());
            } catch (IOException e) {
                corruptRecords.incrementAndGet();
                LOG.warn("确认位点损坏，从最旧段重放（幂等） device={}", deviceId);
                ackSegment = null;
                ackOffset = 0L;
            }
        }

        private synchronized void writeAck() throws IOException {
            byte[] payload;
            try {
                payload = objectMapper.writeValueAsBytes(new AckState(ackSegment, ackOffset));
            } catch (JsonProcessingException e) {
                throw new IOException("确认位点序列化失败", e);
            }
            Path tmp = dir.resolve(ACK_FILE + ".tmp");
            Path target = dir.resolve(ACK_FILE);
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(payload));
                channel.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * 确认位点（原子写入 ack.json）。
     *
     * @param segment 段名；null 表示从头开始
     * @param offset  段内字节偏移
     */
    record AckState(String segment, long offset) {
    }
}
