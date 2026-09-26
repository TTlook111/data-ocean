-- CLARIFICATION_REQUIRED is longer than the original 20-character task status column.
-- Preserve the default and widen the enum-like string for the new no-data terminal state.
ALTER TABLE query_task
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING'
        COMMENT '任务状态：PROCESSING/COMPLETED/FAILED/CANCELLED/TIMEOUT/CLARIFICATION_REQUIRED';
