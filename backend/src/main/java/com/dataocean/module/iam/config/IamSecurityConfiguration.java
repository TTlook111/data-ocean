package com.dataocean.module.iam.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.dataocean.infrastructure.security.ApiErrorWriter;
import com.dataocean.module.iam.security.CurrentIdentityFilter;
import com.dataocean.module.iam.mapper.UserMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.data.redis.config.ConfigureRedisAction;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableRedisIndexedHttpSession(redisNamespace="${spring.session.redis.namespace:dataocean:session}",maxInactiveIntervalInSeconds=28800)
public class IamSecurityConfiguration {
    @Bean ConfigureRedisAction configureRedisAction() { return ConfigureRedisAction.NO_OP; }
    @Bean PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("pbkdf2@SpringSecurity_v5_8",java.util.Map.of("pbkdf2@SpringSecurity_v5_8",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
    @Bean HttpSessionSecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfRepository() { return new HttpSessionCsrfTokenRepository(); }
    @Bean DefaultCookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var cookie=new DefaultCookieSerializer();
        cookie.setCookieName("DATAOCEAN_SESSION");cookie.setCookiePath("/");cookie.setUseHttpOnlyCookie(true);cookie.setUseSecureCookie(secure);cookie.setSameSite("Lax");
        return cookie;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,UserMapper users,ApiErrorWriter errors,Clock clock,
        HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf,
        @Value("${dataocean.session-idle-seconds}") long idle,@Value("${dataocean.session-absolute-seconds}") long absolute) throws Exception {
        var handler=new CsrfTokenRequestAttributeHandler();
        return http.securityContext(context->context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(config->config.csrfTokenRepository(csrf).csrfTokenRequestHandler(handler))
            .authorizeHttpRequests(auth->auth.requestMatchers("/api/v1/auth/csrf","/api/v1/auth/login","/api/v1/health","/error").permitAll().anyRequest().authenticated())
            .exceptionHandling(config->config.authenticationEntryPoint((req,res,error)->errors.write(res,401,"AUTH_REQUIRED","请先登录"))
                .accessDeniedHandler((req,res,error)->errors.write(res,403,"CSRF_INVALID","请求校验已失效，请刷新后重试")))
            .requestCache(cache->cache.disable()).formLogin(login->login.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
            .addFilterAfter(new CurrentIdentityFilter(users,errors,clock,idle,absolute),SecurityContextHolderFilter.class).build();
    }
}
