package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * M5-M19-02：Nginx 安全响应头静态校验（配置文本级守门）。
 *
 * <p>约束：CSP 必须存在且 {@code script-src} 不含 {@code 'unsafe-inline'/'unsafe-eval'}，
 * {@code connect-src} 仅同源；{@code X-Frame-Options DENY}（与 {@code frame-ancestors 'none'}
 * 双保险）；HSTS 只允许出现在 443 server，80 明文响应不得声明 HSTS。
 * 行为级断言见 {@link SecurityHeadersIntegrationTest}，本用例防止配置回退。
 */
class NginxSecurityHeadersConfigTest {

    private static final Pattern CSP_HEADER = Pattern.compile(
            "add_header\\s+Content-Security-Policy\\s+\"([^\"]+)\"\\s+always;", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_SRC = Pattern.compile("script-src([^;]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECT_SRC = Pattern.compile("connect-src([^;]*)", Pattern.CASE_INSENSITIVE);

    @Test
    void httpsServerDeclaresHardenedSecurityHeaders() throws Exception {
        String text = Files.readString(repoRoot().resolve("deploy/nginx/anesop.conf"), StandardCharsets.UTF_8);
        String https = text.substring(indexOf(text, "listen 443 ssl;"));

        assertThat(https).as("443 必须下发 HSTS").contains("Strict-Transport-Security");
        assertThat(https).contains("X-Content-Type-Options \"nosniff\"");
        assertThat(https).contains("X-Frame-Options \"DENY\"");
        assertThat(https).contains("Referrer-Policy");
        assertThat(https).contains("Permissions-Policy");
        assertThat(https).contains("camera=()").contains("microphone=()").contains("geolocation=()");

        Matcher csp = CSP_HEADER.matcher(https);
        assertThat(csp.find()).as("443 必须启用 CSP").isTrue();
        String policy = csp.group(1);
        assertThat(policy)
                .as("CSP 不得使用 inline script / eval")
                .contains("default-src 'self'")
                .contains("object-src 'none'")
                .contains("base-uri 'self'")
                .contains("form-action 'self'")
                .contains("frame-ancestors 'none'");
        assertThat(SCRIPT_SRC.matcher(policy).results().findFirst().orElseThrow().group(1))
                .as("script-src 只允许同源，禁止 unsafe-inline/unsafe-eval")
                .contains("'self'")
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval");
        assertThat(CONNECT_SRC.matcher(policy).results().findFirst().orElseThrow().group(1))
                .as("connect-src 只允许同源（经 nginx /api 反代）")
                .contains("'self'")
                .doesNotContain("*");
    }

    @Test
    void plainHttpServerDoesNotDeclareHsts() throws Exception {
        String text = Files.readString(repoRoot().resolve("deploy/nginx/anesop.conf"), StandardCharsets.UTF_8);
        int httpStart = indexOf(text, "listen 80;");
        int httpsStart = indexOf(text, "listen 443 ssl;");
        assertThat(httpStart).as("必须存在 80 server").isLessThan(httpsStart);
        String httpBlock = text.substring(httpStart, httpsStart);
        assertThat(httpBlock)
                .as("HSTS 不得在明文 80 响应中下发")
                .doesNotContain("Strict-Transport-Security");
    }

    private static int indexOf(String text, String needle) {
        int index = text.indexOf(needle);
        assertThat(index).as("nginx 配置必须包含 %s", needle).isGreaterThanOrEqualTo(0);
        return index;
    }

    private static Path repoRoot() {
        String configured = System.getProperty("anesop.root");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate.resolve("deploy/nginx"))) {
                return candidate;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("deploy/nginx"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到含 deploy/nginx 的仓库根目录");
    }
}
