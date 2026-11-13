package com.anesop.api.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.anesop.api.ApiApplication;
import com.anesop.api.support.EmbeddedPostgresSupport;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * B-2：prod profile 上下文启动失败路径（真实 Spring 容器 + 嵌入式 PG16）。
 *
 * <p>1) 缺失 JWT 密钥 → 启动失败且信息不回显密钥；
 * 2) 数据库存在 {@code demo_*} 账号 → 启动守卫拒绝启动。
 */
class ProductionStartupFailureTest {

    private static final String SECRET = "c3ludGhldGljLXRlc3Qtb25seS1zaWduaW5nLWtleS0wMTIzNDU2Nzg5";

    /** 合成字段密钥（Base64、解码后 32 字节）与 pepper（48 字节），仅测试类路径，不是生产密钥。 */
    private static final String FIELD_KEY = "CD1yp9wWS4C16iRZjsP4Mmec0QtAdarfGU6DuO0nXJE=";
    private static final String PEPPER = "DlGU1x9ipegwc7b5QYTHD1KV2CBjpukxdLf6QoXIEFOW2SFkp+oydbj7Q4bJEVSX";

    /** M1 住院号 pepper（Base64、解码后 44 字节，合成测试值）。 */
    private static final String INPATIENT_PEPPER =
            "c3ludGhldGljLXBlcHBlci0wMTIzNDU2Nzg5LWFuZXNvcC1tMS10ZXN0";

    @AfterAll
    static void dropDatabases() {
        EmbeddedPostgresSupport.dropDatabase("anesop_prod_nokey");
        EmbeddedPostgresSupport.dropDatabase("anesop_prod_demo");
        EmbeddedPostgresSupport.dropDatabase("anesop_prod_nofieldkey");
        EmbeddedPostgresSupport.dropDatabase("anesop_prod_nopepper");
        EmbeddedPostgresSupport.dropDatabase("anesop_prod_noinpatientpepper");
    }

    @Test
    void missingJwtSecretFailsStartupWithoutEchoingSecret() {
        String url = EmbeddedPostgresSupport.jdbcUrl("anesop_prod_nokey");

        assertThatThrownBy(() -> start(url, null, FIELD_KEY, PEPPER, INPATIENT_PEPPER))
                .satisfies(throwable -> {
                    Throwable root = rootCause(throwable);
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage()).contains("ANESOP_JWT_SECRET").contains("拒绝启动");
                });
    }

    @Test
    void demoAccountFailsProdStartup() {
        String url = EmbeddedPostgresSupport.jdbcUrl("anesop_prod_demo");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "postgres", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        insertUser(jdbc, "demo_admin");
        insertUser(jdbc, "demouser");

        assertThatThrownBy(() -> start(url, SECRET, FIELD_KEY, PEPPER, INPATIENT_PEPPER))
                .satisfies(throwable -> {
                    Throwable root = rootCause(throwable);
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage()).contains("demo_*").contains("拒绝启动");
                });
    }

    /**
     * M2-M0-02：字段加密密钥与 pepper 在生产同样 fail-closed（未配置即拒绝启动）。
     *
     * <p>{@code ANESOP_FIELD_KEY} 缺失 → 无法加密手机号；{@code ANESOP_PHONE_PEPPER} 缺失 →
     * 无法生成检索哈希。二者都不得降级为明文或空密钥，否则手机号会以可逆/可碰撞形式入库。
     */
    @Test
    void missingFieldKeyFailsProdStartup() {
        String url = EmbeddedPostgresSupport.jdbcUrl("anesop_prod_nofieldkey");

        assertThatThrownBy(() -> start(url, SECRET, "", PEPPER, INPATIENT_PEPPER))
                .satisfies(throwable -> {
                    Throwable root = rootCause(throwable);
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage()).contains("ANESOP_FIELD_KEY").contains("拒绝启动");
                });
    }

    @Test
    void missingPhonePepperFailsProdStartup() {
        String url = EmbeddedPostgresSupport.jdbcUrl("anesop_prod_nopepper");

        assertThatThrownBy(() -> start(url, SECRET, FIELD_KEY, "", INPATIENT_PEPPER))
                .satisfies(throwable -> {
                    Throwable root = rootCause(throwable);
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage()).contains("ANESOP_PHONE_PEPPER").contains("拒绝启动");
                });
    }

    @Test
    void missingInpatientPepperFailsProdStartup() {
        String url = EmbeddedPostgresSupport.jdbcUrl("anesop_prod_noinpatientpepper");

        assertThatThrownBy(() -> start(url, SECRET, FIELD_KEY, PEPPER, ""))
                .satisfies(throwable -> {
                    Throwable root = rootCause(throwable);
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage()).contains("ANESOP_INPATIENT_PEPPER").contains("拒绝启动");
                });
    }

    private static void start(String jdbcUrl, String secret, String fieldKey, String pepper,
            String inpatientPepper) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ApiApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod");
        // 用命令行参数（最高优先级）覆盖 application.yml，确保 secret 空值/有效值真正生效。
        try (var ignored = builder.run(
                "--server.port=0",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=postgres",
                "--spring.datasource.password=",
                "--anesop.security.jwt.secret=" + (secret == null ? "" : secret),
                "--anesop.security.field-key=" + (fieldKey == null ? "" : fieldKey),
                "--anesop.security.phone-pepper=" + (pepper == null ? "" : pepper),
                "--anesop.m1.inpatient-pepper.value=" + (inpatientPepper == null ? "" : inpatientPepper))) {
            fail("prod 上下文本应启动失败");
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void insertUser(JdbcTemplate jdbc, String username) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, 'synthetic-not-a-real-hash', '合成启动守卫账号', 1, false, 0, 0, false)
                """, UUID.randomUUID().toString(), username);
    }
}
