-- 首个任务同时定位批次，后续项继续使用现有执行、租约和结果模型。
ALTER TABLE gov_generation_task
    ADD COLUMN batch_request_id CHAR(36) CHARACTER SET ascii NULL,
    ADD COLUMN batch_position INT UNSIGNED NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_gov_generation_batch_position (batch_request_id,batch_position),
    ADD CONSTRAINT fk_gov_generation_batch FOREIGN KEY (batch_request_id) REFERENCES gov_generation_task(request_id);
