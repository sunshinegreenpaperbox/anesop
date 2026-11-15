package com.anesop.api.integration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/**
 * 联调冒烟脚本用的独立 PostgreSQL 16 进程（M5-M19-01）。
 *
 * <p>Docker 不可用（EXT-08），`scripts/integration-smoke.sh` 需要启动真实 API 进程；
 * 本入口用 Zonky 嵌入式 PG 16 起一个监听回环地址的实例，打印 {@code JDBC_URL=...} 后阻塞，
 * 由脚本把该 URL 传给 {@code java -jar api-bootstrap.jar}。仅测试作用域，不进入交付物。
 *
 * <p>数据目录位于系统临时目录，进程退出时关闭并删除；不含任何真实患者数据。
 */
public final class SmokePostgresMain {

    private SmokePostgresMain() {
    }

    public static void main(String[] args) throws Exception {
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (IOException ignored) {
                // 进程退出阶段关闭失败无需处理：临时目录由操作系统回收。
            }
        }, "smoke-postgres-close"));
        System.out.println("JDBC_URL=" + postgres.getJdbcUrl("postgres", "postgres"));
        System.out.flush();
        new CountDownLatch(1).await();
    }
}
