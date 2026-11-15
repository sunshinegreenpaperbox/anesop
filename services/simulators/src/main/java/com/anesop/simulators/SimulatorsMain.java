package com.anesop.simulators;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * 模拟器进程入口（{@code scripts/simulators.sh} 调用）。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.anesop.simulators.SimulatorsMain [选项]
 *     --config=PATH        JSON 配置（缺省用内置默认端口 18081/18082/18083）
 *     --his-port=N         HIS 端口覆盖
 *     --pharmacy-port=N    药房端口覆盖
 *     --spd-port=N         SPD 端口覆盖
 *     --status-file=PATH   启动后写入 JSON 状态（供 status 命令读取）
 *     --self-test          启动后立即停止（CI/脚本自检，不阻塞）
 *     --help               打印用法
 * </pre>
 *
 * <p>只绑定 127.0.0.1，只产生 SYNTHETIC 数据，禁止用于生产环境。
 */
public final class SimulatorsMain {

    private SimulatorsMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.help()) {
            System.out.println(usage());
            return;
        }
        SimulatorRuntime runtime = SimulatorRuntime.start(options.toConfig());
        System.out.println(runtime.statusJson());
        System.out.flush();
        if (options.selfTest()) {
            runtime.close();
            return;
        }
        if (options.statusFile() != null) {
            runtime.writeStatus(options.statusFile());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "simulators-shutdown"));
        new CountDownLatch(1).await();
    }

    /** 用法文本（脚本 --help 复用）。 */
    public static String usage() {
        return String.join(System.lineSeparator(),
                "ANESOP 联调模拟器（SYNTHETIC）：HIS / 药房 / SPD",
                "用法：java -cp <classpath> com.anesop.simulators.SimulatorsMain [选项]",
                "  --config=PATH        JSON 配置（缺省 18081/18082/18083）",
                "  --his-port=N         HIS 端口覆盖（0=系统分配）",
                "  --pharmacy-port=N    药房端口覆盖",
                "  --spd-port=N         SPD 端口覆盖",
                "  --status-file=PATH   启动后写入 JSON 状态",
                "  --self-test          启动后立即停止（不阻塞）",
                "  --help               打印本用法");
    }

    /** 命令行参数解析结果（不缓存可变配置对象，避免跨调用共享状态）。 */
    public record Options(
            Path configPath,
            Integer hisPort,
            Integer pharmacyPort,
            Integer spdPort,
            Path statusFile,
            boolean selfTest,
            boolean help) {

        /**
         * 解析命令行。
         *
         * @param args 参数
         * @return 解析结果
         */
        public static Options parse(String[] args) {
            Path configPath = null;
            Path statusFile = null;
            boolean selfTest = false;
            boolean help = false;
            Integer hisPort = null;
            Integer pharmacyPort = null;
            Integer spdPort = null;

            for (String arg : args) {
                String value = valueOf(arg);
                if (arg.equals("--help") || arg.equals("-h")) {
                    help = true;
                } else if (arg.equals("--self-test")) {
                    selfTest = true;
                } else if (value != null && arg.startsWith("--config=")) {
                    configPath = Path.of(value);
                } else if (value != null && arg.startsWith("--status-file=")) {
                    statusFile = Path.of(value);
                } else if (value != null && arg.startsWith("--his-port=")) {
                    hisPort = parseInt(arg, value);
                } else if (value != null && arg.startsWith("--pharmacy-port=")) {
                    pharmacyPort = parseInt(arg, value);
                } else if (value != null && arg.startsWith("--spd-port=")) {
                    spdPort = parseInt(arg, value);
                } else {
                    throw new IllegalArgumentException("未知参数: " + arg);
                }
            }
            return new Options(configPath, hisPort, pharmacyPort, spdPort, statusFile, selfTest, help);
        }

        /**
         * 加载并校验配置（端口覆盖已应用）。
         *
         * @return 新配置实例
         * @throws IOException 配置文件不可读/非法
         */
        public SimulatorConfig toConfig() throws IOException {
            SimulatorConfig config = configPath == null
                    ? SimulatorConfig.defaults()
                    : SimulatorJson.mapper().readValue(Files.readString(configPath), SimulatorConfig.class);
            if (hisPort != null) {
                config.setHis(config.getHis().withPort(hisPort));
            }
            if (pharmacyPort != null) {
                config.setPharmacy(config.getPharmacy().withPort(pharmacyPort));
            }
            if (spdPort != null) {
                config.setSpd(config.getSpd().withPort(spdPort));
            }
            return config.validate();
        }

        private static String valueOf(String arg) {
            int idx = arg.indexOf('=');
            return idx < 0 ? null : arg.substring(idx + 1);
        }

        private static int parseInt(String arg, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("参数不是整数: " + arg, invalid);
            }
        }
    }
}
