ALTER TABLE query_task
    ADD COLUMN suggested_questions JSON NULL COMMENT 'Python 推荐的后续问题列表' AFTER masked_fields;
