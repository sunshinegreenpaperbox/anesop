package com.anesop.api.config;

import com.anesop.api.security.MustChangePasswordFilter;
import com.anesop.api.security.RestAccessDeniedHandler;
import com.anesop.api.security.RestAuthenticationEntryPoint;
import com.anesop.api.security.RevokedAccessTokenValidator;
import com.anesop.m0.api.security.AuthPermissions;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 认证与授权安全配置（S0-07，契约 auth.yaml 0.3.0 / 方案书 §3.9.2、§3.9.5）。
 *
 * <p>授权规则：
 * <ul>
 *   <li>{@code POST /v1/auth/login}、{@code POST /v1/auth/refresh}：permitAll（无令牌换令牌）；</li>
 *   <li>健康检查与 OpenAPI 文档：permitAll，但 springdoc 默认关闭（B-2），路径返回 404；</li>
 *   <li>其余接口：authenticated；细粒度权限用方法级 {@code @PreAuthorize}（示例：
 *       {@code GET /v1/auth/sessions} 需要 {@code PERM_m0:auth:read}）。</li>
 * </ul>
 *
 * <p>访问令牌为 HS256 JWT（15 分钟）；resource server 校验签名、iss、exp 与吊销表（登出即失效）。
 * {@link MustChangePasswordFilter} 在 Bearer 认证后限制强制改密账号的可用接口（S-3）。
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class, RateLimitProperties.class})
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain anesopSecurityFilterChain(HttpSecurity http,
            JwtDecoder jwtDecoder,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            MustChangePasswordFilter mustChangePasswordFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterAfter(mustChangePasswordFilter, BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * 强制改密过滤器只挂在 Spring Security 链上（Bearer 认证之后），
     * 关闭 Boot 对 Filter Bean 的自动 Servlet 注册，避免重复执行。
     */
    @Bean
    public FilterRegistrationBean<MustChangePasswordFilter> mustChangePasswordFilterRegistration(
            MustChangePasswordFilter filter) {
        FilterRegistrationBean<MustChangePasswordFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtSecretProvider secretProvider) {
        SecretKey key = secretProvider.key();
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtSecretProvider secretProvider,
            JwtProperties properties,
            RevokedAccessTokenValidator revokedAccessTokenValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretProvider.key())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, revokedAccessTokenValidator));
        return decoder;
    }

    /**
     * 把 JWT claim 映射为 Spring Security 权限：
     * {@code roles} → {@code ROLE_<code>}，{@code permissions} → {@code PERM_<code>}。
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("username");
        converter.setJwtGrantedAuthoritiesConverter(this::authorities);
        return converter;
    }

    private List<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority(AuthPermissions.roleAuthority(role))));
        }
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions != null) {
            permissions.forEach(permission ->
                    authorities.add(new SimpleGrantedAuthority(AuthPermissions.permissionAuthority(permission))));
        }
        return authorities;
    }
}
