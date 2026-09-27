ALTER TABLE conversation_context_summary
    ADD COLUMN permission_scope_fingerprint VARCHAR(64) NULL
        COMMENT '有效 IAM-SIMPLE-1 资源范围摘要指纹，覆盖授权有效期变化';
