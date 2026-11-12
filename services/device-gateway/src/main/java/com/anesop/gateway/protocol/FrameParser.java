package com.anesop.gateway.protocol;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 模拟设备协议 v1 的行帧解析器。
 *
 * <p>帧格式（UTF-8，单行以 {@code \n} 结束，字段以 {@code |} 分隔，共 11 段）：
 * <pre>
 * DEV|&lt;deviceId&gt;|&lt;seq&gt;|&lt;measuredAt ISO8601&gt;|&lt;hr&gt;|&lt;spo2&gt;|&lt;nibpSys&gt;|&lt;nibpDia&gt;|
 *    &lt;etco2&gt;|&lt;temp&gt;|&lt;quality&gt;
 * </pre>
 *
 * <p>解析只做语法校验：字段数、帧头、设备标识字符集、序号非负、时间可解析、数值格式、
 * 质量码范围。单位换算、物理合理性、时钟偏差属于语义层，交给 {@code VitalNormalizer}。
 * 任一不满足即抛 {@link FrameParseException}，由调用方丢弃并计数。
 */
public final class FrameParser {

    /** 帧头。 */
    public static final String PREFIX = "DEV";

    /** 协议 v1 固定字段数。 */
    public static final int FIELD_COUNT = 11;

    /** 设备标识字符集：与缓冲目录名、数据库列长度约束一致。 */
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /**
     * 解析一帧。
     *
     * @param line       一行报文（不含换行符）
     * @param receivedAt 网关接收时间（服务端时钟）
     * @return 解析后的设备帧
     * @throws FrameParseException 帧非法
     */
    public DeviceFrame parse(String line, Instant receivedAt) {
        if (line == null || line.isBlank()) {
            throw new FrameParseException(FrameError.EMPTY_FRAME, "");
        }
        String[] fields = line.split("\\|", -1);
        if (fields.length != FIELD_COUNT) {
            throw new FrameParseException(FrameError.FIELD_COUNT, "");
        }
        if (!PREFIX.equals(fields[0])) {
            throw new FrameParseException(FrameError.BAD_PREFIX, "prefix");
        }
        String deviceId = fields[1];
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            throw new FrameParseException(FrameError.BAD_DEVICE_ID, "deviceId");
        }
        long seq = parseSeq(fields[2]);
        Instant measuredAt = parseMeasuredAt(fields[3]);
        int quality = parseQuality(fields[10]);
        return new DeviceFrame(
                deviceId,
                seq,
                measuredAt,
                receivedAt,
                Measurement.parse(fields[4], "hr"),
                Measurement.parse(fields[5], "spo2"),
                Measurement.parse(fields[6], "nibpSys"),
                Measurement.parse(fields[7], "nibpDia"),
                Measurement.parse(fields[8], "etco2"),
                Measurement.parse(fields[9], "temp"),
                quality);
    }

    private static long parseSeq(String token) {
        long seq;
        try {
            seq = Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new FrameParseException(FrameError.BAD_SEQ, "seq");
        }
        if (seq < 0) {
            throw new FrameParseException(FrameError.BAD_SEQ, "seq");
        }
        return seq;
    }

    private static Instant parseMeasuredAt(String token) {
        try {
            return Instant.parse(token);
        } catch (DateTimeParseException first) {
            try {
                return OffsetDateTime.parse(token).toInstant();
            } catch (DateTimeParseException second) {
                throw new FrameParseException(FrameError.BAD_MEASURED_AT, "measuredAt");
            }
        }
    }

    private static int parseQuality(String token) {
        int quality;
        try {
            quality = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new FrameParseException(FrameError.BAD_QUALITY, "quality");
        }
        if (quality < 0 || quality > 2) {
            throw new FrameParseException(FrameError.BAD_QUALITY, "quality");
        }
        return quality;
    }
}
