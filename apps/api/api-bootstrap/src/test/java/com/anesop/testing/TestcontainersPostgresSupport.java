package com.anesop.testing;

import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers PostgreSQL 16 模板（S0-11）。
 *
 * <p>Docker 当前不可用（EXT-08）：所有使用本类的测试必须标 {@link Tag @Tag("docker")}，
 * surefire 默认 {@code excludedGroups=docker} 不执行；用 {@code mvn -Pdocker verify} 反向激活。
 * {@link Testcontainers#disabledWithoutDocker()} 在无 Docker 环境下把用例标记为 skipped，
 * 不会伪造通过。
 *
 * <p><b>镜像</b>：暂用 tag {@code postgres:16-alpine}。AGENTS.md 要求镜像按 digest 引用；
 * EXT-08 解除、可访问镜像仓库后必须改为 {@code postgres:16-alpine@sha256:<digest>}，
 * 并在 docs/testing/README.md 记录 digest 与验证日期。
 *
 * <p>本类不启动容器，只提供模板；容器生命周期由测试类的 {@code @Container} 管理。
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
public final class TestcontainersPostgresSupport {

    /** 测试镜像 tag（待 EXT-08 解除后补 digest）。 */
    public static final String POSTGRES_IMAGE = "postgres:16-alpine";

    /** 合成测试库名；不含任何真实数据。 */
    public static final String DATABASE_NAME = "anesop_test";

    /** 合成测试账号（仅容器内有效，非生产凭据）。 */
    public static final String USERNAME = "anesop_test";

    /** 合成测试口令（仅容器内有效，非生产凭据）。 */
    public static final String PASSWORD = "anesop_test_only";

    private TestcontainersPostgresSupport() {
    }

    /**
     * 新建 PostgreSQL 16 容器模板（未启动）。
     *
     * @return 可交给测试类 {@code @Container} 管理的容器
     */
    public static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(DATABASE_NAME)
                .withUsername(USERNAME)
                .withPassword(PASSWORD)
                .withReuse(false);
    }
}
