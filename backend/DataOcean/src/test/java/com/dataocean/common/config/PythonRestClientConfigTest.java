package com.dataocean.common.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class PythonRestClientConfigTest {

    @Test
    void everyPythonClientBeanSendsTheConfiguredInternalToken() throws IOException {
        List<String> observedTokenDigests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            observedTokenDigests.add(sha256(token));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        String configuredToken = "unit-test-internal-token";
        try {
            PythonRestClientConfig config = new PythonRestClientConfig();
            setField(config, "pythonBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            setField(config, "internalToken", configuredToken);

            List<RestClient> clients = List.of(
                    config.pythonRestClient(),
                    config.pythonShortTimeoutRestClient(),
                    config.pythonHealthRestClient(3000, 3000));
            for (RestClient client : clients) {
                client.get().uri("/probe").retrieve().toBodilessEntity();
            }

            assertThat(observedTokenDigests)
                    .containsExactlyElementsOf(clients.stream()
                            .map(ignored -> sha256(configuredToken))
                            .toList());
        } finally {
            server.stop(0);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("测试配置字段不存在: " + name, e);
        }
    }

    private static String sha256(String value) {
        if (value == null) {
            return "missing";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
