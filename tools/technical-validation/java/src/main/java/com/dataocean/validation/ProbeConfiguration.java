package com.dataocean.validation;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

@Configuration
public class ProbeConfiguration {
    @Bean
    MybatisPlusInterceptor pagination() {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    InMemoryUserDetailsManager users(@Value("${probe.password}") String password) {
        // 用户只用于隔离验证；会话仍由真实 Spring Security / Redis 保存。
        return new InMemoryUserDetailsManager(User.withUsername("probe")
                .password("{noop}" + password).roles("PROBE").build());
    }

    @Bean
    @Order(1)
    SecurityFilterChain internalSecurity(HttpSecurity http,
            @Value("${probe.secret-directory}") String directory) throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(publicKey(Path.of(directory, "python-public.pem"))).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("probe-python"),
                jwt -> jwt.getAudience().contains("probe-java")
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
        return http.securityMatcher("/internal/**").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(decoder))).build();
    }

    @Bean
    SecurityFilterChain userSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/probe/health", "/probe/csrf", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(login -> login.successHandler((request, response, auth) -> response.setStatus(204))
                        .failureHandler((request, response, error) -> response.setStatus(401)))
                .logout(logout -> logout.logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, error) -> response.setStatus(403))).build();
    }

    @Bean
    NimbusJwtEncoder serviceEncoder(@Value("${probe.secret-directory}") String directory) throws Exception {
        var privateBytes = pem(Path.of(directory, "java-private.pem"));
        var privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        var key = new RSAKey.Builder(publicKey(Path.of(directory, "java-public.pem")))
                .privateKey(privateKey).keyID("java-probe").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    RestClient pythonClient(@Value("${probe.python-url}") String url, NimbusJwtEncoder encoder) {
        return RestClient.builder().baseUrl(url).requestInterceptor((request, body, execution) -> {
            var now = Instant.now();
            var claims = JwtClaimsSet.builder().issuer("probe-java").subject("java-service")
                    .audience(List.of("probe-python")).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
            var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("java-probe").build();
            request.getHeaders().setBearerAuth(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue());
            return execution.execute(request, body);
        }).build();
    }

    private static byte[] pem(Path path) throws Exception {
        return Base64.getDecoder().decode(Files.readString(path)
                .replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    }

    private static RSAPublicKey publicKey(Path path) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(path)));
    }
}
