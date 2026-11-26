package com.dataocean.validation;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ProbeController {
    private final ProbeItemMapper items;
    private final ProbeService service;
    private final RestClient python;
    private final SessionRepository<?> sessions;

    ProbeController(ProbeItemMapper items, ProbeService service, RestClient python, SessionRepository<?> sessions) {
        this.items = items;
        this.service = service;
        this.python = python;
        this.sessions = sessions;
    }

    public record CommandDTO(String requestId, String commandId, String kind, int runVersion) {}
    public record StageDTO(String requestId, int runVersion, String stage) {}
    public record ManifestDTO(int expectedVersion, String generation, String collectionName) {}
    public record ItemVO(String id, String label) {}
    public record PageVO(long total, List<ItemVO> records) {}

    @GetMapping("/probe/health")
    Map<String, String> health() { return Map.of("status", "ok", "java", System.getProperty("java.version")); }

    @GetMapping("/probe/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header", token.getHeaderName(), "parameter", token.getParameterName());
    }

    @GetMapping("/probe/me")
    Map<String, String> me(Authentication authentication) { return Map.of("username", authentication.getName()); }

    @GetMapping("/probe/items")
    PageVO items() {
        var page = items.selectPage(new Page<>(2, 2), new QueryWrapper<ProbeItemPO>().orderByAsc("id"));
        return new PageVO(page.getTotal(), page.getRecords().stream()
                .map(item -> new ItemVO(item.getId().toString(), StrUtil.trim(item.getLabel()))).toList());
    }

    @PostMapping("/probe/tasks")
    Map<String, Object> start(@RequestBody CommandDTO command) {
        service.create(command);
        python.post().uri("/internal/tasks").body(command).retrieve().toBodilessEntity();
        return service.task(command.requestId());
    }

    @GetMapping("/probe/tasks/{id}")
    Map<String, Object> task(@PathVariable String id) { return service.task(id); }

    @PostMapping("/probe/tasks/{id}/cancel")
    Map<String, Object> cancel(@PathVariable String id) {
        service.cancel(id);
        python.post().uri("/internal/tasks/{id}/cancel", id).retrieve().toBodilessEntity();
        return service.task(id);
    }

    @PostMapping("/probe/tasks/{id}/resume")
    Map<String, Object> resume(@PathVariable String id) {
        var command = service.resume(id);
        python.post().uri("/internal/tasks").body(command).retrieve().toBodilessEntity();
        return service.task(id);
    }

    @PostMapping("/internal/stage")
    Map<String, Object> stage(@RequestBody StageDTO stage) {
        service.stage(stage);
        return service.task(stage.requestId());
    }

    @GetMapping("/internal/tasks/{id}")
    Map<String, Object> internalTask(@PathVariable String id) { return service.task(id); }

    @GetMapping(value = "/probe/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", defaultValue = "0-0") String cursor,
            HttpServletRequest request) {
        var session = request.getSession(false);
        String sessionId = session.getId();
        var emitter = new SseEmitter(15_000L);
        // HTTP 客户端是真实订阅者；断线只结束订阅，不触发新任务。
        CompletableFuture.runAsync(() -> {
            try (var lines = python.get().uri(uri -> uri.path("/internal/tasks/{id}/events")
                            .queryParam("cursor", cursor).build(id)).retrieve()
                    .body(java.io.InputStream.class)) {
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(lines, java.nio.charset.StandardCharsets.UTF_8));
                String eventId = null;
                String eventType = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    // 每次交付前检查原会话仍存在，退出后不得继续交付。
                    if (sessions.findById(sessionId) == null) break;
                    if (line.startsWith("id: ")) eventId = line.substring(4);
                    if (line.startsWith("event: ")) eventType = line.substring(7);
                    if (line.startsWith("data: ")) {
                        emitter.send(SseEmitter.event().id(eventId).name(eventType).data(line.substring(6)));
                    }
                }
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @GetMapping("/probe/manifest")
    Map<String, Object> manifest() { return service.manifest(); }

    @PostMapping("/probe/manifest")
    Map<String, Object> switchManifest(@RequestBody ManifestDTO manifest) {
        service.switchManifest(manifest);
        return service.manifest();
    }
}
