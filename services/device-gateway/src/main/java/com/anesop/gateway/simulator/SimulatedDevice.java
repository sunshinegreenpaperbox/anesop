package com.anesop.gateway.simulator;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * 模拟设备（协议 v1）：按固定周期向网关推送<b>合成</b>体征帧。
 *
 * <p>只产生 SYNTHETIC 数据，不含任何患者标识；用于 POC 演示与联调，
 * 不访问真实设备（EXT-04 待 D-06 确认设备清单与协议）。
 *
 * <p>用法：{@code java -cp device-gateway.jar com.anesop.gateway.simulator.SimulatedDevice \
 * [host] [port] [deviceId] [frames] [intervalMs]}
 */
public final class SimulatedDevice {

    private static final int DEFAULT_FRAMES = 60;
    private static final long DEFAULT_INTERVAL_MS = 1000L;

    private SimulatedDevice() {
    }

    /**
     * 程序入口。
     *
     * @param args host、port、deviceId、frames、intervalMs（均可省略）
     * @throws IOException          连接失败
     * @throws InterruptedException 被中断
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19090;
        String deviceId = args.length > 2 ? args[2] : "SIM-MON-01";
        int frames = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_FRAMES;
        long intervalMs = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_INTERVAL_MS;

        try (Socket socket = new Socket(host, port);
                Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            for (int index = 1; index <= frames; index++) {
                writer.write(frame(deviceId, index, Instant.now()));
                writer.write('\n');
                writer.flush();
                if (index < frames && intervalMs > 0) {
                    Thread.sleep(intervalMs);
                }
            }
        }
    }

    /**
     * 生成一帧合成数据（数值由序号决定，可复现）。
     *
     * @param deviceId   设备标识
     * @param seq        帧序号
     * @param measuredAt 测量时间
     * @return 协议行（不含换行）
     */
    public static String frame(String deviceId, long seq, Instant measuredAt) {
        int hr = 70 + (int) (seq % 10);
        int spo2 = 97 + (int) (seq % 3);
        int nibpSys = 115 + (int) (seq % 7);
        int nibpDia = 75 + (int) (seq % 5);
        String etco2 = String.format(Locale.ROOT, "%.1f", 34.0d + (seq % 5) * 0.5d);
        String temp = String.format(Locale.ROOT, "%.1f", 36.4d + (seq % 3) * 0.1d);
        return "DEV|" + deviceId + "|" + seq + "|" + measuredAt + "|" + hr + "|" + spo2 + "|"
                + nibpSys + "|" + nibpDia + "|" + etco2 + "|" + temp + "|0";
    }
}
