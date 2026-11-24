-- 每条记录对应一次实际提供方请求。发出前登记，响应返回后补齐结果及用量。
CREATE TABLE mdl_provider_call (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    module_code VARCHAR(20) CHARACTER SET ascii NOT NULL,
    origin_id VARCHAR(100) CHARACTER SET ascii NOT NULL,
    node_code VARCHAR(40) CHARACTER SET ascii NOT NULL,
    model_revision_id BIGINT UNSIGNED NOT NULL,
    model_name VARCHAR(120) NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii NULL,
    elapsed_ms INT UNSIGNED NULL,
    input_tokens INT UNSIGNED NULL,
    output_tokens INT UNSIGNED NULL,
    started_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    review_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    recorded_at DATETIME(3) GENERATED ALWAYS AS (COALESCE(completed_at, review_at)) STORED,
    KEY ix_mdl_provider_call_time (recorded_at, id),
    KEY ix_mdl_provider_call_origin (module_code, origin_id),
    FOREIGN KEY (node_code) REFERENCES mdl_node_assignment(node_code),
    FOREIGN KEY (model_revision_id) REFERENCES mdl_config_revision(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 保留已有治理历史。统一记录已接管的任务只计实际请求，避免重复计入任务级汇总回执。
CREATE VIEW mdl_diagnostic_call AS
SELECT CONCAT('provider:', p.id) AS id, p.node_code, p.model_revision_id, p.model_name,
       IF(p.completed_at IS NOT NULL AND p.failure_code IS NULL, 'SUCCEEDED', 'FAILED') AS state,
       IF(p.completed_at IS NULL, 'MODEL_RESULT_UNKNOWN', p.failure_code) AS failure_code,
       p.elapsed_ms, p.input_tokens, p.output_tokens, prior.input_limits, p.recorded_at
FROM mdl_provider_call p LEFT JOIN gov_model_call prior
       ON p.module_code = 'GOVERNANCE' AND prior.request_id = p.origin_id
UNION ALL
SELECT CAST(g.id AS CHAR), g.node_code, t.model_revision_id, g.model_name,
       g.state, g.failure_code, g.elapsed_ms, g.input_tokens, g.output_tokens, g.input_limits, g.recorded_at
FROM gov_model_call g JOIN gov_generation_task t ON t.request_id = g.request_id
WHERE COALESCE(g.failure_code, '') <> 'MODEL_RECORD_UNAVAILABLE'
  AND NOT EXISTS (
      SELECT 1 FROM mdl_provider_call p WHERE p.module_code = 'GOVERNANCE' AND p.origin_id = g.request_id
  );
