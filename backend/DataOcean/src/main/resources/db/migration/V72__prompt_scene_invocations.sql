-- 全场景策略与独立调用回执；新增策略只作为待审草稿，不伪造人工批准。
CREATE TABLE prompt_invocation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 invocation_id VARCHAR(64) NOT NULL,
 task_id VARCHAR(100) NULL,
 scene_code VARCHAR(50) NOT NULL,
 version_no INT NOT NULL,
 source VARCHAR(16) NOT NULL,
 contract_version VARCHAR(20) NOT NULL,
 content_hash CHAR(64) NOT NULL,
 fallback_reason VARCHAR(32) NOT NULL,
 outcome VARCHAR(32) NOT NULL,
 duration_ms BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE INDEX uk_prompt_invocation (invocation_id),
 INDEX idx_prompt_invocation_scene (scene_code, id),
 INDEX idx_prompt_invocation_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略调用元信息，不存提示词和业务输入';

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled, status)
SELECT 'schema_linking', '问题理解与规划', '问数', '', 0, 0, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE template_code = 'schema_linking');
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status)
SELECT id, 1, '{"formatVersion":1,"system":"你是数据问题规划助手。结合授权目录和已审核知识，明确用户意图、粒度与必要字段；信息不足时请求澄清。","user":"{{input}}"}', '场景纳管初始草稿，须校验并人工审核后激活', 0, 'DRAFT'
FROM prompt_template t WHERE template_code = 'schema_linking'
AND NOT EXISTS (SELECT 1 FROM prompt_template_version v WHERE v.template_id = t.id);

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled, status)
SELECT 'sql_semantic_check', 'SQL 语义核对', '问数', '', 0, 0, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE template_code = 'sql_semantic_check');
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status)
SELECT id, 1, '{"formatVersion":1,"system":"你是 SQL 业务核对助手。逐项核对问题、SQL、已审核业务事实和枚举字面值，给出有依据的核对结论。","user":"{{input}}"}', '场景纳管初始草稿，须校验并人工审核后激活', 0, 'DRAFT'
FROM prompt_template t WHERE template_code = 'sql_semantic_check'
AND NOT EXISTS (SELECT 1 FROM prompt_template_version v WHERE v.template_id = t.id);

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled, status)
SELECT 'result_verification', '结果核验', '问数', '', 0, 0, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE template_code = 'result_verification');
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status)
SELECT id, 1, '{"formatVersion":1,"system":"你是结果核对助手。结合用户问题检查已保护结果的列和粒度，给出简洁回答或说明需要澄清的口径。","user":"{{input}}"}', '场景纳管初始草稿，须校验并人工审核后激活', 0, 'DRAFT'
FROM prompt_template t WHERE template_code = 'result_verification'
AND NOT EXISTS (SELECT 1 FROM prompt_template_version v WHERE v.template_id = t.id);

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled, status)
SELECT 'field_semantic', '字段解释候选', '知识治理', '', 0, 0, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE template_code = 'field_semantic');
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status)
SELECT id, 1, '{"formatVersion":1,"system":"你是字段释义起草助手。区分已知依据与推测，明确列出待确认项，用中文向业务人员解释字段。","user":"{{input}}"}', '场景纳管初始草稿，须校验并人工审核后激活', 0, 'DRAFT'
FROM prompt_template t WHERE template_code = 'field_semantic'
AND NOT EXISTS (SELECT 1 FROM prompt_template_version v WHERE v.template_id = t.id);

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled, status)
SELECT 'semantic_supplement', '语义知识补写', '知识治理', '', 0, 0, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE template_code = 'semantic_supplement');
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status)
SELECT id, 1, '{"formatVersion":1,"system":"你是业务知识起草助手。使用已审核术语和合规字段起草简洁释义，完整列出来源依赖与待确认点。","user":"{{input}}"}', '场景纳管初始草稿，须校验并人工审核后激活', 0, 'DRAFT'
FROM prompt_template t WHERE template_code = 'semantic_supplement'
AND NOT EXISTS (SELECT 1 FROM prompt_template_version v WHERE v.template_id = t.id);

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled, status)
SELECT 'business_knowledge', '业务知识候选', '知识治理', '', 0, 0, 'DRAFT'
WHERE NOT EXISTS (SELECT 1 FROM prompt_template WHERE template_code = 'business_knowledge');
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status)
SELECT id, 1, '{"formatVersion":1,"system":"你是业务知识整理助手。按对象类型整理用途、粒度、关系或指标，缺少依据的部分留待人工补充。","user":"{{input}}"}', '场景纳管初始草稿，须校验并人工审核后激活', 0, 'DRAFT'
FROM prompt_template t WHERE template_code = 'business_knowledge'
AND NOT EXISTS (SELECT 1 FROM prompt_template_version v WHERE v.template_id = t.id);


