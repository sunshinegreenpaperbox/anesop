package com.anesop.gateway.protocol;

import com.anesop.gateway.buffer.BufferCapacityException;
import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.metrics.GatewayMetricNames;
import com.anesop.gateway.model.VitalSample;
import com.anesop.gateway.normalize.VitalNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 设备 TCP 接入服务（POC：Java 原生 {@link ServerSocket} + 虚拟线程）。
 *
 * <p>协议：UTF-8 行帧，{@code \n} 分隔，单帧上限 {@code maxFrameBytes}；超限或非法帧
 * 丢弃并计数，不中断连接（一台设备的重连不应影响其他设备）。
 *
 * <p>每帧处理链：读行 → {@link FrameParser}（语法）→ {@link VitalNormalizer}（单位/范围/
 * 质量/时钟）→ {@link LocalBuffer}（落盘 fsync）。只有落盘成功才计 buffered；
 * 上传由 {@code VitalUploader} 异步完成，接入线程不做数据库 IO。
 *
 * <p>生产建议：改用 Netty（事件驱动、背压、连接数上限、TLS），见 docs/gateway/README.md §8。
 * 本 POC 显式不引入 Netty，避免未审批依赖（H-05）。
 */
@Component
public final class TcpDeviceServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TcpDeviceServer.class);

    /** 超长行标记：读到后丢弃整行并计数。 */
    private static final String OVERSIZE_MARKER = "\u0000OVERSIZE";

    private final GatewayProperties properties;
    private final FrameParser parser;
    private final VitalNormalizer normalizer;
    private final LocalBuffer buffer;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger activeConnections = new AtomicInteger();

    private ServerSocket serverSocket;
    private volatile int boundPort = -1;
    private volatile boolean running;

    /**
     * 构造接入服务。
     *
     * @param properties    网关配置
     * @param parser        帧解析器
     * @param normalizer    标准化器
     * @param buffer        本地缓冲
     * @param meterRegistry 指标注册表
     */
    public TcpDeviceServer(GatewayProperties properties, FrameParser parser, VitalNormalizer normalizer,
            LocalBuffer buffer, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.parser = parser;
        this.normalizer = normalizer;
        this.buffer = buffer;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 启动监听（端口 0 时由系统分配，测试用）。
     *
     * @throws IOException 端口被占用或无权限
     */
    @PostConstruct
    public void start() throws IOException {
        if (!properties.tcp().enabled()) {
            LOG.info("TCP 接入已关闭（anesop.gateway.tcp.enabled=false）");
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(properties.tcp().bindAddress(), properties.tcp().port()));
        this.serverSocket = socket;
        this.boundPort = socket.getLocalPort();
        this.running = true;
        executor.submit(this::acceptLoop);
        LOG.info("设备 TCP 接入已启动 bind={}:{}", properties.tcp().bindAddress(), boundPort);
    }

    /**
     * 实际监听端口（未启用时为 -1）。
     *
     * @return 端口
     */
    public int port() {
        return boundPort;
    }

    /**
     * 是否正在监听。
     *
     * @return true 表示监听中
     */
    public boolean listening() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * 当前设备连接数。
     *
     * @return 连接数
     */
    public int activeConnections() {
        return activeConnections.get();
    }

    @Override
    @PreDestroy
    public void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                LOG.warn("关闭监听端口失败 cause={}", e.getMessage());
            }
        }
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                activeConnections.incrementAndGet();
                executor.submit(() -> handleConnection(client));
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                LOG.warn("接受设备连接失败 cause={}", e.getMessage());
            }
        }
    }

    private void handleConnection(Socket client) {
        try (Socket socket = client) {
            socket.setTcpNoDelay(true);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            while (running) {
                String line = readFrame(input, properties.tcp().maxFrameBytes());
                if (line == null) {
                    return;
                }
                if (OVERSIZE_MARKER.equals(line)) {
                    meterRegistry.counter(GatewayMetricNames.FRAMES_INVALID, "reason", "OVERSIZE_FRAME")
                            .increment();
                    LOG.warn("丢弃超长帧 limit={}", properties.tcp().maxFrameBytes());
                } else {
                    processLine(line);
                }
            }
        } catch (IOException e) {
            LOG.info("设备连接断开 cause={}", e.getMessage());
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    private void processLine(String line) {
        DeviceFrame frame;
        try {
            frame = parser.parse(line, Instant.now());
        } catch (FrameParseException e) {
            meterRegistry.counter(GatewayMetricNames.FRAMES_INVALID, "reason", e.error().name()).increment();
            LOG.warn("丢弃非法帧 reason={} field={} length={}", e.error(), e.field(), line.length());
            return;
        }
        meterRegistry.counter(GatewayMetricNames.FRAMES_RECEIVED, "device", frame.deviceId()).increment();
        VitalSample sample = normalizer.normalize(frame, properties.source());
        try {
            buffer.append(sample);
            meterRegistry.counter(GatewayMetricNames.FRAMES_BUFFERED, "device", frame.deviceId()).increment();
        } catch (BufferCapacityException e) {
            meterRegistry.counter(GatewayMetricNames.FRAMES_FAILED, "device", frame.deviceId()).increment();
            LOG.error("缓冲容量不足，拒收帧 device={} limit={}", frame.deviceId(), properties.buffer().maxBytesPerDevice());
        } catch (IOException e) {
            meterRegistry.counter(GatewayMetricNames.FRAMES_FAILED, "device", frame.deviceId()).increment();
            LOG.error("缓冲写入失败 device={} cause={}", frame.deviceId(), e.getMessage());
        }
    }

    private static String readFrame(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);
        boolean oversize = false;
        while (true) {
            int current = input.read();
            if (current < 0) {
                if (lineBuffer.size() == 0 && !oversize) {
                    return null;
                }
                return oversize ? OVERSIZE_MARKER : decode(lineBuffer);
            }
            if (current == '\n') {
                return oversize ? OVERSIZE_MARKER : decode(lineBuffer);
            }
            if (lineBuffer.size() < maxBytes) {
                lineBuffer.write(current);
            } else {
                oversize = true;
            }
        }
    }

    private static String decode(ByteArrayOutputStream lineBuffer) {
        byte[] bytes = lineBuffer.toByteArray();
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
