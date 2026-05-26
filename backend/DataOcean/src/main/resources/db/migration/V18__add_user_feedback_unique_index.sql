-- Prevent duplicate feedback from the same user for the same query field.
ALTER TABLE user_feedback
    ADD UNIQUE INDEX uk_user_feedback_once (
        user_id,
        query_task_id,
        column_meta_id,
        feedback_type
    );
