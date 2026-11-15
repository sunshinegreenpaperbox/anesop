package com.anesop.simulators;

/**
 * 单个模拟外部系统的配置（JSON 文件可覆盖全部字段）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code port}：监听端口；{@code 0} 表示由操作系统分配（测试用），默认见
 *       {@link SimulatorConfig} 各域默认端口。</li>
 *   <li>{@code latencyMs}：每次请求固定延迟；大于调用方超时时间即为“超时”场景。</li>
 *   <li>{@code failFirstRequests}：前 N 个请求按 {@code errorStatus} 失败，之后恢复正常
 *       （验证“重试后成功”）。</li>
 *   <li>{@code errorStatus}：故障注入使用的 HTTP 状态码（默认 500）。</li>
 *   <li>{@code failureMode}：故障模式，见 {@link FailureMode}。</li>
 * </ul>
 */
public final class SimulatorConfig {

    /** HIS 模拟器默认端口。 */
    public static final int DEFAULT_HIS_PORT = 18081;
    /** 药房模拟器默认端口。 */
    public static final int DEFAULT_PHARMACY_PORT = 18082;
    /** SPD 模拟器默认端口。 */
    public static final int DEFAULT_SPD_PORT = 18083;

    private Endpoint his = new Endpoint(DEFAULT_HIS_PORT);
    private Endpoint pharmacy = new Endpoint(DEFAULT_PHARMACY_PORT);
    private Endpoint spd = new Endpoint(DEFAULT_SPD_PORT);

    /** 全默认配置：端口 18081/18082/18083，无延迟、无故障注入。 */
    public static SimulatorConfig defaults() {
        return new SimulatorConfig();
    }

    public Endpoint getHis() {
        return copy(his);
    }

    public void setHis(Endpoint his) {
        this.his = his == null ? new Endpoint(DEFAULT_HIS_PORT) : his;
    }

    public Endpoint getPharmacy() {
        return copy(pharmacy);
    }

    public void setPharmacy(Endpoint pharmacy) {
        this.pharmacy = pharmacy == null ? new Endpoint(DEFAULT_PHARMACY_PORT) : pharmacy;
    }

    public Endpoint getSpd() {
        return copy(spd);
    }

    /** 对外只返回端点配置副本：调用方无法通过 getter 修改内部状态（config 由 set 方法整体替换）。 */
    private static Endpoint copy(Endpoint source) {
        Endpoint copy = new Endpoint(source.getPort());
        copy.setLatencyMs(source.getLatencyMs());
        copy.setFailFirstRequests(source.getFailFirstRequests());
        copy.setErrorStatus(source.getErrorStatus());
        copy.setFailureMode(source.getFailureMode());
        return copy;
    }

    public void setSpd(Endpoint spd) {
        this.spd = spd == null ? new Endpoint(DEFAULT_SPD_PORT) : spd;
    }

    /**
     * 覆盖三个端口并校验（测试用：0 表示系统分配）。
     *
     * @param hisPort      HIS 端口
     * @param pharmacyPort 药房端口
     * @param spdPort      SPD 端口
     * @return this
     */
    public SimulatorConfig withPorts(int hisPort, int pharmacyPort, int spdPort) {
        his.setPort(hisPort);
        pharmacy.setPort(pharmacyPort);
        spd.setPort(spdPort);
        return validate();
    }

    /**
     * 校验并归一化（null 端点补默认值）。
     *
     * @return this
     * @throws IllegalArgumentException 任一端口/延迟/状态码非法
     */
    public SimulatorConfig validate() {
        his = normalize(his, DEFAULT_HIS_PORT, "his");
        pharmacy = normalize(pharmacy, DEFAULT_PHARMACY_PORT, "pharmacy");
        spd = normalize(spd, DEFAULT_SPD_PORT, "spd");
        return this;
    }

    private static Endpoint normalize(Endpoint endpoint, int defaultPort, String name) {
        Endpoint effective = endpoint == null ? new Endpoint(defaultPort) : endpoint;
        if (effective.getPort() < 0 || effective.getPort() > 65535) {
            throw new IllegalArgumentException(name + ".port 必须在 0–65535 之间");
        }
        if (effective.getLatencyMs() < 0) {
            throw new IllegalArgumentException(name + ".latencyMs 不得为负");
        }
        if (effective.getFailFirstRequests() < 0) {
            throw new IllegalArgumentException(name + ".failFirstRequests 不得为负");
        }
        if (effective.getErrorStatus() < 400 || effective.getErrorStatus() > 599) {
            throw new IllegalArgumentException(name + ".errorStatus 必须在 400–599 之间");
        }
        if (effective.getFailureMode() == null) {
            effective.setFailureMode(FailureMode.NONE);
        }
        return effective;
    }

    /** 单个外部系统的端点配置（Jackson 通过 setter 反序列化）。 */
    public static final class Endpoint {

        private int port;
        private int latencyMs;
        private int failFirstRequests;
        private int errorStatus = 500;
        private FailureMode failureMode = FailureMode.NONE;

        public Endpoint() {
            this(0);
        }

        public Endpoint(int port) {
            this.port = port;
            this.latencyMs = 0;
            this.failFirstRequests = 0;
            this.errorStatus = 500;
            this.failureMode = FailureMode.NONE;
        }

        /** 返回替换端口后的副本（配置对象不可从外部就地修改）。 */
        public Endpoint withPort(int newPort) {
            Endpoint copy = copy(this);
            copy.port = newPort;
            return copy;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(int latencyMs) {
            this.latencyMs = latencyMs;
        }

        public int getFailFirstRequests() {
            return failFirstRequests;
        }

        public void setFailFirstRequests(int failFirstRequests) {
            this.failFirstRequests = failFirstRequests;
        }

        public int getErrorStatus() {
            return errorStatus;
        }

        public void setErrorStatus(int errorStatus) {
            this.errorStatus = errorStatus;
        }

        public FailureMode getFailureMode() {
            return failureMode;
        }

        public void setFailureMode(FailureMode failureMode) {
            this.failureMode = failureMode;
        }
    }
}
