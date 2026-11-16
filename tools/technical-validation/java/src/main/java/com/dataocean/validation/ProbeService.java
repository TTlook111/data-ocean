package com.dataocean.validation;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 仅保存验证任务及生效指针，不构成正式业务模型。 */
@Service
public class ProbeService {
    private final JdbcTemplate jdbc;

    ProbeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void create(ProbeController.CommandDTO command) {
        var existing = jdbc.queryForList("SELECT * FROM probe_tasks WHERE request_id=?", command.requestId());
        if (!existing.isEmpty()) {
            var row = existing.get(0);
            if (!command.commandId().equals(row.get("command_id")) || !command.kind().equals(row.get("kind"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            return;
        }
        jdbc.update("INSERT INTO probe_tasks(request_id,command_id,kind,status,run_version) VALUES(?,?,?,'ACCEPTED',1)",
                command.requestId(), command.commandId(), command.kind());
    }

    public Map<String, Object> task(String id) {
        return jdbc.queryForMap("SELECT * FROM probe_tasks WHERE request_id=?", id);
    }

    @Transactional
    public void cancel(String id) {
        jdbc.update("UPDATE probe_tasks SET status='CANCELLING' WHERE request_id=? AND status NOT IN ('COMPLETED','CANCELLED')", id);
    }

    @Transactional
    public ProbeController.CommandDTO resume(String id) {
        var task = jdbc.queryForMap("SELECT * FROM probe_tasks WHERE request_id=? FOR UPDATE", id);
        if (!"WAITING".equals(task.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT);
        int version = ((Number) task.get("run_version")).intValue() + 1;
        jdbc.update("UPDATE probe_tasks SET status='ACCEPTED',run_version=? WHERE request_id=?", version, id);
        return new ProbeController.CommandDTO(id, id + "-resume-" + version, "resume", version);
    }

    @Transactional
    public void stage(ProbeController.StageDTO stage) {
        var task = jdbc.queryForMap("SELECT * FROM probe_tasks WHERE request_id=? FOR UPDATE", stage.requestId());
        if (((Number) task.get("run_version")).intValue() != stage.runVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        String current = task.get("status").toString();
        if (current.equals("CANCELLED") || current.equals("COMPLETED")
                || (current.equals("CANCELLING") && !stage.stage().equals("CANCELLED"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        // 完成计数用于独立核对重复投递、恢复或 SSE 重连没有重复执行。
        if (stage.stage().equals("PREPARED")) {
            jdbc.update("UPDATE probe_tasks SET status='RUNNING',prepare_count=prepare_count+1 WHERE request_id=?", stage.requestId());
        } else if (stage.stage().equals("COMPLETED")) {
            jdbc.update("UPDATE probe_tasks SET status='COMPLETED',complete_count=complete_count+1 WHERE request_id=?", stage.requestId());
        } else {
            jdbc.update("UPDATE probe_tasks SET status=? WHERE request_id=?", stage.stage(), stage.requestId());
        }
    }

    public Map<String, Object> manifest() { return jdbc.queryForMap("SELECT * FROM probe_manifest WHERE id=1"); }

    @Transactional
    public void switchManifest(ProbeController.ManifestDTO manifest) {
        int changed = jdbc.update("UPDATE probe_manifest SET version=version+1,generation=?,collection_name=? WHERE id=1 AND version=?",
                manifest.generation(), manifest.collectionName(), manifest.expectedVersion());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
    }
}
