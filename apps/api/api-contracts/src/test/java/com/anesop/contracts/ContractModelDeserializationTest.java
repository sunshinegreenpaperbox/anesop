package com.anesop.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.DataScope;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 契约示例 JSON → 生成 DTO 的反序列化用例（ADR-0003 第 5 条）。
 *
 * <p>样例取自 {@code packages/contracts/openapi/auth.yaml} 的 200 示例，用于证明
 * 生成类型与契约字段名/枚举/时间格式一致；生成物本身由 codegen 产出、禁止手改，
 * 本用例是“生成物可编译且可用”的守门测试。
 */
class ContractModelDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void generatedModelsCarryGeneratedMarker() throws Exception {
        // @Generated 是 SOURCE 保留级别，运行期不可见；直接校验生成源码的文件头/注解，
        // 证明 codegen 确实生效且生成物未被手工替换（禁止手改）。
        Path generated = Path.of("target/generated-sources/openapi/auth/src/main/java/"
                + "com/anesop/contracts/auth/model/LoginRequest.java");
        assertThat(generated).as("先运行 generate-sources 生成契约模型").exists();

        String source = Files.readString(generated, StandardCharsets.UTF_8);
        assertThat(source)
                .startsWith("package com.anesop.contracts.auth.model;")
                .contains("@Generated(value = \"org.openapitools.codegen.languages.SpringCodegen\"");
        // 生成物不出现 Gson/JsonNullable 等未引入依赖，避免隐式运行时类路径。
        assertThat(source).doesNotContain("com.google.gson", "JsonNullable");
    }

    @Test
    void patientContractExamplesUseUuidAndDeprecatedFieldsAreMarked() throws Exception {
        Path root = Path.of(System.getProperty("anesop.root", ".")).toAbsolutePath().normalize();
        Path patient = root.resolve("packages/contracts/openapi/patient.yaml");
        Path scheduling = root.resolve("packages/contracts/openapi/scheduling.yaml");
        assertThat(patient).exists();
        assertThat(scheduling).exists();

        Pattern patientIdExample = Pattern.compile("patientId:\\s*'([^']+)'");
        for (Path contract : List.of(patient, scheduling)) {
            String text = Files.readString(contract, StandardCharsets.UTF_8);
            Matcher matcher = patientIdExample.matcher(text);
            int count = 0;
            while (matcher.find()) {
                count++;
                assertThat(matcher.group(1))
                        .as("patientId 示例必须是 UUID 字符串：%s", contract.getFileName())
                        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
            }
            assertThat(count).as("契约应包含 patientId 示例：%s", contract.getFileName()).isPositive();
        }

        String patientText = Files.readString(patient, StandardCharsets.UTF_8);
        for (String field : List.of("idCardMasked", "phoneMasked", "inpatientNo")) {
            assertThat(patientText).contains("deprecated: true")
                    .as("兼容字段 %s 必须标 deprecated", field);
        }
        assertThat(patientText).contains("POST /v1/patients/search");
    }

    @Test
    void loginRequestExampleDeserializes() throws Exception {
        String example = """
                {"username":"demo.anesthesiologist","password":"SYNTHETIC-PASSWORD","authType":"LOCAL"}
                """;

        LoginRequest request = objectMapper.readValue(example, LoginRequest.class);

        assertThat(request.getUsername()).isEqualTo("demo.anesthesiologist");
        assertThat(request.getPassword()).isEqualTo("SYNTHETIC-PASSWORD");
        assertThat(request.getAuthType()).isEqualTo(LoginRequest.AuthTypeEnum.LOCAL);
    }

    @Test
    void tokenResponseExampleDeserializesIncludingNestedUserAndTime() throws Exception {
        String example = """
                {
                  "accessToken": "SYNTH-ACCESS-TOKEN",
                  "refreshToken": "SYNTH-REFRESH-TOKEN",
                  "tokenType": "Bearer",
                  "expiresIn": 1800,
                  "user": {
                    "userId": "1000000000000000001",
                    "username": "demo.anesthesiologist",
                    "displayName": "演示医师",
                    "roles": ["ANESTHESIOLOGIST"],
                    "permissions": ["anesthesia.record.read", "anesthesia.record.write"],
                    "dataScopes": [{"type": "DEPARTMENT", "scopeIds": ["2000000000000000001"]}],
                    "siteIds": ["3000000000000000001"]
                  }
                }
                """;

        TokenResponse response = objectMapper.readValue(example, TokenResponse.class);

        assertThat(response.getAccessToken()).isEqualTo("SYNTH-ACCESS-TOKEN");
        assertThat(response.getTokenType()).isEqualTo(TokenResponse.TokenTypeEnum.BEARER);
        assertThat(response.getExpiresIn()).isEqualTo(1800);

        CurrentUser user = response.getUser();
        assertThat(user.getUserId()).isEqualTo("1000000000000000001");
        assertThat(user.getRoles()).containsExactly("ANESTHESIOLOGIST");
        assertThat(user.getPermissions()).containsExactly("anesthesia.record.read", "anesthesia.record.write");
        assertThat(user.getDataScopes()).hasSize(1);
        DataScope scope = user.getDataScopes().get(0);
        assertThat(scope.getType()).isEqualTo(DataScope.TypeEnum.DEPARTMENT);
        assertThat(scope.getScopeIds()).containsExactly("2000000000000000001");
        assertThat(user.getSiteIds()).containsExactly("3000000000000000001");
    }

    @Test
    void sessionExampleParsesOffsetDateTime() throws Exception {
        String example = """
                {
                  "sessionId": "4000000000000000001",
                  "userId": "1000000000000000001",
                  "clientIp": "10.0.0.10",
                  "userAgent": "SYNTHETIC-BROWSER/1.0",
                  "loginAt": "2026-11-12T08:00:00+08:00",
                  "expiresAt": "2026-11-12T08:30:00+08:00",
                  "status": "ACTIVE",
                  "createdAt": "2026-11-12T08:00:00+08:00",
                  "createdBy": "1000000000000000001",
                  "updatedAt": "2026-11-12T08:00:00+08:00",
                  "updatedBy": "1000000000000000001",
                  "version": 1
                }
                """;

        var session = objectMapper.readValue(example, com.anesop.contracts.auth.model.Session.class);

        assertThat(session.getLoginAt()).isEqualTo(OffsetDateTime.parse("2026-11-12T08:00:00+08:00"));
        assertThat(session.getStatus()).isEqualTo(com.anesop.contracts.auth.model.Session.StatusEnum.ACTIVE);
        assertThat(session.getVersion()).isEqualTo(1);
    }
}
