-- 仅用于命名 H2 内存库，真实 MySQL 迁移另行验收。
DROP TABLE IF EXISTS quality_rule_operation;
DROP TABLE IF EXISTS quality_rule_write_guard;
CREATE TABLE quality_rule_write_guard (id INT PRIMARY KEY);
INSERT INTO quality_rule_write_guard (id) VALUES (1);
DROP TABLE IF EXISTS metadata_quality_rule_revision;
DROP TABLE IF EXISTS metadata_quality_rule;
CREATE TABLE metadata_quality_rule (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, rule_code VARCHAR(100), template_code VARCHAR(100),
 rule_name VARCHAR(200), dimension VARCHAR(40), severity VARCHAR(20), description VARCHAR(500),
 check_target VARCHAR(20), check_type VARCHAR(20), check_expression VARCHAR(500), threshold DECIMAL(12,4),
 enabled INT, deduction_points DECIMAL(8,2), builtin INT, scope_type VARCHAR(30), datasource_id BIGINT,
 target_table_name VARCHAR(255), target_column_name VARCHAR(255), parameters_json CLOB, config_version INT,
 deleted_at TIMESTAMP, deleted_by BIGINT, created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP
);
CREATE TABLE metadata_quality_rule_revision (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, rule_id BIGINT, rule_code VARCHAR(100), template_code VARCHAR(100),
 config_version INT, action VARCHAR(30), rule_name VARCHAR(200), dimension VARCHAR(40), severity VARCHAR(20),
 description VARCHAR(500), check_target VARCHAR(20), check_type VARCHAR(20), scope_type VARCHAR(30),
 datasource_id BIGINT, target_table_name VARCHAR(255), target_column_name VARCHAR(255), parameters_json CLOB,
 enabled INT, deduction_points DECIMAL(8,2), builtin INT, deleted INT, changed_by BIGINT, changed_at TIMESTAMP
);
CREATE TABLE quality_rule_operation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT NOT NULL, operation_id VARCHAR(36) NOT NULL,
 request_hash VARCHAR(64), action VARCHAR(16), source_rule_id BIGINT, source_scope_type VARCHAR(16),
 source_datasource_id BIGINT, target_scope_type VARCHAR(16), target_datasource_id BIGINT,
 outcome VARCHAR(16) NOT NULL, result_rule_id BIGINT, result_version INT, error_code INT,
 error_message VARCHAR(500), created_at TIMESTAMP NOT NULL, completed_at TIMESTAMP,
 CONSTRAINT uk_quality_rule_operation UNIQUE(operator_user_id, operation_id)
);
