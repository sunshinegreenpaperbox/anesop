package com.anesop.simulators;

import com.anesop.simulators.his.HisSimulator;
import com.anesop.simulators.pharmacy.PharmacySimulator;
import com.anesop.simulators.spd.SpdSimulator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟器运行时：同时启动 HIS / 药房 / SPD 三个 HTTP 模拟服务，统一输出 JSON 状态。
 *
 * <p>只绑定回环地址，只产生 SYNTHETIC 数据；用于契约测试（进程内）与
 * {@code scripts/simulators.sh}（独立进程）。
 */
public final class SimulatorRuntime implements AutoCloseable {

    private final SimulatorConfig config;
    private final HisSimulator his;
    private final PharmacySimulator pharmacy;
    private final SpdSimulator spd;
    private boolean started;

    private SimulatorRuntime(SimulatorConfig config) {
        this.config = config;
        this.his = new HisSimulator(config.getHis());
        this.pharmacy = new PharmacySimulator(config.getPharmacy());
        this.spd = new SpdSimulator(config.getSpd());
    }

    /**
     * 创建并启动全部模拟服务。
     *
     * @param config 已校验配置
     * @return 运行时（调用方负责 close）
     * @throws IOException 端口占用等启动失败
     */
    public static SimulatorRuntime start(SimulatorConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("模拟器配置不得为空");
        }
        config.validate();
        SimulatorRuntime runtime = new SimulatorRuntime(config);
        runtime.startAll();
        return runtime;
    }

    private void startAll() throws IOException {
        try {
            his.start();
            pharmacy.start();
            spd.start();
            started = true;
        } catch (IOException failure) {
            close();
            throw failure;
        }
    }

    public HisSimulator his() {
        return his;
    }

    public PharmacySimulator pharmacy() {
        return pharmacy;
    }

    public SpdSimulator spd() {
        return spd;
    }

    /** 状态 JSON：{@code status/synthetic/servers[]}，端口为实际监听端口。 */
    public String statusJson() throws IOException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", started ? "UP" : "DOWN");
        status.put("synthetic", true);
        status.put("pid", ProcessHandle.current().pid());
        List<Map<String, Object>> servers = new ArrayList<>(3);
        servers.add(describe("his", his.port(), his.requestCount()));
        servers.add(describe("pharmacy", pharmacy.port(), pharmacy.requestCount()));
        servers.add(describe("spd", spd.port(), spd.requestCount()));
        status.put("servers", servers);
        return SimulatorJson.mapper().writeValueAsString(status);
    }

    private static Map<String, Object> describe(String name, int port, int requestCount) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", name);
        server.put("port", port);
        server.put("baseUrl", "http://127.0.0.1:" + port);
        server.put("requestCount", requestCount);
        return server;
    }

    /** 把状态 JSON 写入文件（{@code scripts/simulators.sh} 的 start/status 使用）。 */
    public void writeStatus(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, statusJson() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        his.close();
        pharmacy.close();
        spd.close();
        started = false;
    }
}
