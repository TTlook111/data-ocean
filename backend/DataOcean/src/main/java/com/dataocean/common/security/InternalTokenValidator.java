package com.dataocean.common.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部服务间令牌的唯一来源与校验器
 * <p>
 * {@code /internal/**} 在 Spring Security 层面不作为公开路径放行，其信任来自本令牌。
 * 因此这里不提供任何可用的默认值：令牌缺失、含空白或过短时，应用直接拒绝启动，
 * 而不是带着一个可预测的凭据继续对外提供服务。
 * </p>
 * <p>
 * 入站校验（{@link InternalTokenFilter}）与出站发送（{@code PythonRestClientConfig}）
 * 都从本类取值，避免两侧出现"发送用一个值、校验用另一个值"的静默发散。
 * </p>
 *
 * @author dataocean
 */
@Component
@Slf4j
public class InternalTokenValidator {

    /** 令牌配置项名称，仅用于报错文案 */
    static final String PROPERTY_NAME = "dataocean.internal.token";

    /** 最小长度：长度只是熵的弱代理，配合文档中的生成命令即可 */
    static final int MIN_TOKEN_LENGTH = 32;

    private final String token;

    public InternalTokenValidator(@Value("${" + PROPERTY_NAME + ":}") String token) {
        this.token = token;
    }

    /**
     * 启动时校验令牌配置。
     * <p>
     * 刻意不做任何 profile 判断：此前按 profile 区分的守卫会因
     * {@code spring.profiles.active} 被写死在配置文件里而失效。
     * </p>
     *
     * @throws IllegalStateException 令牌缺失、含空白或长度不足时
     */
    @PostConstruct
    void validateConfiguration() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    PROPERTY_NAME + " 未配置。该令牌是 /internal/** 的唯一防线，不存在默认值。"
                            + "请通过环境变量 INTERNAL_TOKEN 提供，或在被 Git 忽略的 "
                            + "config/application-local.yml 中设置 dataocean.internal.token；"
                            + "两侧必须是同一个值");
        }
        // 拒绝任何空白：配置里混入行尾空白或换行会让校验通过、比较却永远失败，
        // 表现为"没有报错但内部调用全部 403"，排查成本极高。
        if (token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(
                    PROPERTY_NAME + " 不能包含空格、制表符或换行，请检查配置是否误写成多行");
        }
        if (token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    PROPERTY_NAME + " 至少需要 " + MIN_TOKEN_LENGTH + " 个字符，当前为 "
                            + token.length() + " 个。可用 openssl rand -hex 32 生成");
        }
        log.info("内部服务令牌已配置，长度={}", token.length());
    }

    /**
     * 常量时间比较请求携带的令牌。
     *
     * @param candidate 请求头中的令牌，可为 null
     * @return 是否匹配
     */
    public boolean matches(String candidate) {
        if (candidate == null || token == null) {
            return false;
        }
        // 先编码为 UTF-8 字节：请求头按 latin-1 解码，攻击者可以构造非 ASCII 内容，
        // 而直接比较字符串的实现在这种情况下会抛异常（把 403 变成 500）。
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 出站调用使用的令牌值。
     *
     * @return 已校验的令牌
     */
    public String token() {
        return token;
    }
}
