package com.anesop.gateway.model;

/**
 * 数据质量码（与 {@code m2_anes.anesthesia_vital.quality} 的 CHECK 约束一致）。
 *
 * <p>方案书 §3.6.4：用标记而非丢弃。超范围、单位不符、时钟偏差一律降级为"可疑"，
 * 仍保留数值供质控按标记过滤，避免脏数据参与指标计算。
 */
public final class Quality {

    /** 正常。 */
    public static final int NORMAL = 0;

    /** 可疑（超物理范围 / 单位不可信 / 时钟偏差或补传）。 */
    public static final int SUSPECT = 1;

    /** 缺失（设备标注或全部指标缺省）。 */
    public static final int MISSING = 2;

    /** 超时（保留，供心跳超时判定使用）。 */
    public static final int TIMEOUT = 3;

    private Quality() {
    }

    /**
     * 取两个质量码中较差者（数值越大越差）。
     *
     * @param left  质量码
     * @param right 质量码
     * @return 较差的码
     */
    public static int worst(int left, int right) {
        return Math.max(left, right);
    }
}
