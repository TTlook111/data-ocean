-- 节点可独立配置，运行职责继续由接入该节点的业务模块承担。
ALTER TABLE mdl_node_assignment
    ADD COLUMN capability VARCHAR(12) CHARACTER SET ascii NOT NULL DEFAULT 'GENERATION';

INSERT INTO mdl_node_assignment (node_code,capability) VALUES
    ('document-plan','GENERATION'),
    ('document-write','GENERATION'),
    ('question-suggestion','GENERATION'),
    ('intent','GENERATION'),
    ('clarification','GENERATION'),
    ('history-summary','GENERATION'),
    ('sql-generate','GENERATION'),
    ('sql-semantic-check','GENERATION'),
    ('output-meaning','GENERATION'),
    ('sql-repair','GENERATION'),
    ('result-explanation','GENERATION'),
    ('rag-embedding','EMBEDDING'),
    ('rag-rerank','RERANK'),
    ('feedback-similarity','EMBEDDING');
