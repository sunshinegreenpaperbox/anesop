package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * B-3：Nginx `/api/` 访问日志不得把查询参数（可能含 PHI）写入磁盘。
 *
 * <p>静态校验配置文本：`/api/` location 必须使用不含 `$request`/`$args`/`$query_string`
 * 的自定义格式 `anesop_no_query`，或显式 `access_log off`。本用例与
 * `scripts/compose-check.sh` 第 12 节互为补充（测试侧守门，防止脚本被绕过）。
 */
class NginxPhiLogConfigTest {

    private static final Pattern API_LOCATION =
            Pattern.compile("location\\s+/api/\\s*\\{(.*?)\\n    \\}", Pattern.DOTALL);

    @Test
    void apiLocationDoesNotLogQueryString() throws Exception {
        Path config = repoRoot().resolve("deploy/nginx/anesop.conf");
        assertThat(config).as("Nginx 配置必须存在").exists();
        String text = Files.readString(config, StandardCharsets.UTF_8);

        Matcher matcher = API_LOCATION.matcher(text);
        assertThat(matcher.find()).as("必须存在 location /api/ 块").isTrue();
        String block = matcher.group(1);
        assertThat(block)
                .as("/api/ 访问日志不得包含 query（$request 含查询串，$args/$query_string 即查询串）")
                .doesNotContainPattern("\\$args")
                .doesNotContainPattern("\\$query_string")
                .doesNotContainPattern("\\$request(?![A-Za-z0-9_])");
        assertThat(block).as("/api/ 必须使用无 query 的自定义日志格式").contains("access_log")
                .contains("anesop_no_query");
        Matcher format = Pattern.compile("log_format\\s+anesop_no_query(.*?);", Pattern.DOTALL)
                .matcher(text);
        assertThat(format.find()).as("必须定义 log_format anesop_no_query").isTrue();
        assertThat(format.group(1)).as("自定义格式本身也不得引用 query")
                .doesNotContain("$args")
                .doesNotContain("$query_string")
                .doesNotContainPattern("\\$request(?![A-Za-z0-9_])");
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
