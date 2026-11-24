-- 正文沿用已持久化的知识任务队列，规划行继续使用原输入和结果结构。
ALTER TABLE kn_document_plan
    ADD COLUMN operation VARCHAR(16) CHARACTER SET ascii NOT NULL DEFAULT 'PLAN',
    ADD COLUMN parent_plan_id BIGINT UNSIGNED NULL,
    ADD COLUMN suggestion_index INT UNSIGNED NULL,
    ADD COLUMN applied_document_id BIGINT UNSIGNED NULL,
    ADD KEY ix_kn_plan_suggestion (parent_plan_id, suggestion_index, id),
    ADD CONSTRAINT ck_kn_plan_operation CHECK (
        (operation='PLAN' AND parent_plan_id IS NULL AND suggestion_index IS NULL AND applied_document_id IS NULL)
        OR (operation='WRITE' AND parent_plan_id IS NOT NULL AND suggestion_index IS NOT NULL)
    );
