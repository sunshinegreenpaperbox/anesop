package com.anesop.m2.api.acquisition;

/**
 * 设备采集子域常量（M3-M8-01，契约 device.yaml 0.1.0）。
 *
 * <p>取值集合与数据库 CHECK 约束、网关协议 v1 保持一致：设备类型/状态、指标编码与单位、
 * 质量码、数据来源、拒绝原因码。集中在一处，避免服务层与控制器各写一份白名单。
 */
public final class AcquisitionConstants {

    /** 设备状态：在线。 */
    public static final String STATUS_ONLINE = "ONLINE";

    /** 设备状态：离线（运维显式置离线，或由 lastSeenAt 超时推导）。 */
    public static final String STATUS_OFFLINE = "OFFLINE";

    /** 设备状态：维护中（运维显式状态，不被自动改写）。 */
    public static final String STATUS_MAINTENANCE = "MAINTENANCE";

    /** 设备类型：监护仪。 */
    public static final String TYPE_MONITOR = "MONITOR";

    /** 设备类型：麻醉机。 */
    public static final String TYPE_ANESTHESIA_MACHINE = "ANESTHESIA_MACHINE";

    /** 设备类型：输注泵。 */
    public static final String TYPE_INFUSION_PUMP = "INFUSION_PUMP";

    /** 设备类型：呼吸机。 */
    public static final String TYPE_VENTILATOR = "VENTILATOR";

    /** 设备类型：其他。 */
    public static final String TYPE_OTHER = "OTHER";

    /** 指标编码：心率。 */
    public static final String METRIC_HR = "HR";

    /** 指标编码：脉搏血氧饱和度。 */
    public static final String METRIC_SPO2 = "SPO2";

    /** 指标编码：无创收缩压。 */
    public static final String METRIC_NIBP_SYS = "NIBP_SYS";

    /** 指标编码：无创舒张压。 */
    public static final String METRIC_NIBP_DIA = "NIBP_DIA";

    /** 指标编码：呼气末二氧化碳。 */
    public static final String METRIC_ETCO2 = "ETCO2";

    /** 指标编码：体温。 */
    public static final String METRIC_TEMP = "TEMP";

    /** 规范单位：次/分。 */
    public static final String UNIT_BPM = "bpm";

    /** 规范单位：百分比。 */
    public static final String UNIT_PERCENT = "%";

    /** 规范单位：毫米汞柱。 */
    public static final String UNIT_MMHG = "mmHg";

    /** 规范单位：摄氏度。 */
    public static final String UNIT_CELSIUS = "C";

    /** 数据来源：设备自动采集。 */
    public static final String SOURCE_AUTO = "AUTO";

    /** 数据来源：手工补录。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 数据来源：补传重放。 */
    public static final String SOURCE_REPLAY = "REPLAY";

    /** 拒绝原因：序号缺失或为负。 */
    public static final String REJECT_SEQ_INVALID = "SEQ_INVALID";

    /** 拒绝原因：测量时间缺失。 */
    public static final String REJECT_MEASURED_AT_MISSING = "MEASURED_AT_MISSING";

    /** 拒绝原因：质量码越界（仅允许 0–3）。 */
    public static final String REJECT_QUALITY_OUT_OF_RANGE = "QUALITY_OUT_OF_RANGE";

    /** 拒绝原因：数值超出物理护栏（非临床范围，超范围本应打质量标记）。 */
    public static final String REJECT_VALUE_OUT_OF_RANGE = "VALUE_OUT_OF_RANGE";

    /** 拒绝原因：来源枚举非法。 */
    public static final String REJECT_SOURCE_INVALID = "SOURCE_INVALID";

    /** 拒绝原因：质量标记拼接后超长（>200）。 */
    public static final String REJECT_FLAGS_TOO_LONG = "FLAGS_TOO_LONG";

    /** 质量码：正常。 */
    public static final int QUALITY_NORMAL = 0;

    /** 质量码：可疑。 */
    public static final int QUALITY_SUSPECT = 1;

    /** 质量码：缺失。 */
    public static final int QUALITY_MISSING = 2;

    /** 质量码：超时。 */
    public static final int QUALITY_TIMEOUT = 3;

    /** 质量标记最大长度（与 V4 quality_flags varchar(200) 对齐）。 */
    public static final int MAX_QUALITY_FLAGS_LENGTH = 200;

    /** 批次键最大长度（与 V14 batch_key varchar(128) 对齐）。 */
    public static final int MAX_BATCH_KEY_LENGTH = 128;

    /** 单批最大帧数（防止单请求放大）。 */
    public static final int MAX_FRAMES_PER_BATCH = 500;

    /** 单页最大采样帧数。 */
    public static final int MAX_PAGE_SIZE = 200;

    private AcquisitionConstants() {
    }
}
