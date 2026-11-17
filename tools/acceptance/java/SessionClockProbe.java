import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * 只调整验收客户端自身会话的时限字段，直接验证真实 Redis 与实际认证过滤链。
 */
public class SessionClockProbe {

    /**
     * 输入仅经标准输入传递，输出不包含 Cookie、会话标识或令牌。
     */
    public static void main(String[] args) throws Exception {
        var configuration = VerificationEnvironment.read(Path.of(args[0]));

        var json = JsonMapper.builder().build();

        var request = json.readTree(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));

        String sessionId = request.path("sessionId").asString();

        String field = request.path("field").asString();
        if (!sessionId.matches("[0-9a-fA-F-]{36}") || !Set.of("dataocean.authenticatedAt", "dataocean.lastUserActivityAt").contains(field)) {
            throw new IllegalArgumentException("会话或时限字段不符合本批验收约定");
        }

        String host = configuration.getProperty("spring.data.redis.host", "127.0.0.1");
        if (!Set.of("127.0.0.1", "localhost").contains(host)) {
            throw new IllegalStateException("仅验证本机开发 Redis");
        }

        var standalone = new RedisStandaloneConfiguration(host, Integer.parseInt(configuration.getProperty("spring.data.redis.port", "6379")));
        standalone.setPassword(RedisPassword.of(configuration.getProperty("spring.data.redis.password")));
        standalone.setDatabase(Integer.parseInt(configuration.getProperty("spring.data.redis.database", "0")));

        var factory = new LettuceConnectionFactory(standalone);
        factory.afterPropertiesSet();
        factory.start();
        try {
            // 序列化与实际 Spring Session 保持一致，仅访问本次客户端给出的已存在会话。
            var redis = new RedisTemplate<String, Object>();
            redis.setConnectionFactory(factory);
            redis.setKeySerializer(RedisSerializer.string());
            redis.setHashKeySerializer(RedisSerializer.string());
            redis.setValueSerializer(new JdkSerializationRedisSerializer());
            redis.setHashValueSerializer(new JdkSerializationRedisSerializer());
            redis.afterPropertiesSet();

            String namespace = configuration.getProperty("spring.session.redis.namespace", "dataocean:session");

            String key = namespace + ":sessions:" + sessionId;
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                throw new IllegalStateException("本次验收客户端的会话不存在");
            }
            if (request.has("epoch")) {
                redis.opsForHash().put(key, "sessionAttr:" + field, request.path("epoch").asLong());
            }

            Object value = redis.opsForHash().get(key, "sessionAttr:" + field);
            System.out.println(json.writeValueAsString(Map.of("epoch", value)));
        } finally {
            factory.destroy();
        }
    }
}
