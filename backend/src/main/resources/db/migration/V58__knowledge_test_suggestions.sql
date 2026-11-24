-- 标准题建议沿用知识模型任务队列，按发起人和确切确认文档回看。
ALTER TABLE kn_document_plan
    DROP CHECK ck_kn_plan_operation,
    ADD COLUMN question_document_id BIGINT UNSIGNED NULL,
    ADD COLUMN question_revision_id BIGINT UNSIGNED NULL,
    ADD KEY ix_kn_question_suggestion (source_id, requested_by, question_document_id, question_revision_id, id),
    ADD CONSTRAINT ck_kn_plan_operation CHECK (
        (operation='PLAN' AND parent_plan_id IS NULL AND suggestion_index IS NULL AND applied_document_id IS NULL
            AND question_document_id IS NULL AND question_revision_id IS NULL)
        OR (operation='WRITE' AND parent_plan_id IS NOT NULL AND suggestion_index IS NOT NULL
            AND question_document_id IS NULL AND question_revision_id IS NULL)
        OR (operation='QUESTION' AND parent_plan_id IS NULL AND suggestion_index IS NULL AND applied_document_id IS NULL
            AND question_document_id IS NOT NULL AND question_revision_id IS NOT NULL)
    );
