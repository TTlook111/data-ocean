package com.dataocean.module.governance.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

/**
 * 治理 AI 服务 JWT 链与可独立关闭的固定服务连接。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
public class GovernanceAiConfiguration {

    /**
     * 仅覆盖工作者访问的内部路径，普通浏览器会话不会进入该身份链。
     */
    @Bean
    @Order(1)
    SecurityFilterChain governanceWorkerSecurity(
            HttpSecurity http, @Value("${dataocean.governance-ai.python-public-key-file}") String file)
            throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey(file)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("dataocean-python"),
                token -> token.getAudience() != null
                                && token.getAudience().contains("dataocean-java")
                                && "governance-worker".equals(token.getSubject())
                                && token.getExpiresAt() != null
                                && "dataocean-python".equals(token.getHeaders().get("kid"))
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return http.securityMatcher(
                        "/internal/v1/governance/**", "/internal/v1/knowledge/**", "/internal/v1/model-calls/**")
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(config -> config.disable())
                .authorizeHttpRequests(config -> config.anyRequest().authenticated())
                .oauth2ResourceServer(config -> config.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    /**
     * 以固定端点和短时非对称令牌调用 Python 内部任务服务。
     */
    @Bean
    RestClient governancePythonClient(
            @Value("${dataocean.governance-ai.python-url}") String url,
            @Value("${dataocean.governance-ai.java-private-key-file}") String privateFile,
            @Value("${dataocean.governance-ai.java-public-key-file}") String publicFile)
            throws GeneralSecurityException, IOException {
        URI endpoint = URI.create(url);
        if (!("https".equals(endpoint.getScheme())
                        || "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()))
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null) {
            // 仅宿主机环回开发入口允许明文 HTTP。
            throw new IllegalArgumentException("治理内部服务地址需使用 HTTPS 或本机环回");
        }
        var key = (RSAPrivateKey)
                KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pem(privateFile)));
        var jwk = new RSAKey.Builder(publicKey(publicFile))
                .privateKey(key)
                .keyID("dataocean-java")
                .build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory(5))
                .requestInterceptor((request, body, execution) -> {
                    // 用户 Cookie 不进入内部服务连接。
                    Instant now = Instant.now();
                    var claims = JwtClaimsSet.builder()
                            .issuer("dataocean-java")
                            .subject("governance-dispatcher")
                            .audience(List.of("dataocean-python"))
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(300))
                            .build();
                    var header = JwsHeader.with(SignatureAlgorithm.RS256)
                            .keyId("dataocean-java")
                            .build();
                    request.getHeaders()
                            .setBearerAuth(encoder.encode(JwtEncoderParameters.from(header, claims))
                                    .getTokenValue());
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * 连接测试有独立的 15 秒模型预算，沿用已有内部身份与目的地。
     */
    @Bean
    RestClient modelTestPythonClient(@Qualifier("governancePythonClient") RestClient client) {
        return client.mutate().requestFactory(requestFactory(20)).build();
    }

    /**
     * 异步文档规划需要更长的模型响应时间，仍沿用同一个内部签名连接。
     */
    @Bean
    RestClient knowledgePlanPythonClient(@Qualifier("governancePythonClient") RestClient client) {
        return client.mutate().requestFactory(requestFactory(65)).build();
    }

    /**
     * 为两种内部动作分别设定有限的读超时。
     */
    private JdkClientHttpRequestFactory requestFactory(int readSeconds) {
        // Uvicorn 的本机 HTTP 入口使用 HTTP/1.1，避免 h2c 升级中断 POST 正文。
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));
        return factory;
    }

    /**
     * 从文件加载可信公钥，路径只由服务配置提供。
     */
    private RSAPublicKey publicKey(String file) throws GeneralSecurityException, IOException {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(file)));
    }

    /**
     * 解码运行环境注入的标准 PEM。
     */
    private byte[] pem(String file) throws IOException {
        return Base64.getDecoder()
                .decode(Files.readString(Path.of(file))
                        .replaceAll("-----[^-]+-----", "")
                        .replaceAll("\\s", ""));
    }
}
