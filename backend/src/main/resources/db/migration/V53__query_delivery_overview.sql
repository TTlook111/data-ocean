-- 请求与交付是不同的统计时点，解释与清理不改变已交付事实。
ALTER TABLE qry_request
    ADD COLUMN delivered_at DATETIME(3) NULL,
    ADD INDEX idx_qry_source_submitted (source_id, created_at);

-- 旧请求的已交付状态只在交付时写入 updated_at。解释、终态取消和结果清理均不更新该时刻。
UPDATE qry_request SET delivered_at=updated_at WHERE state='RESULT_DELIVERED';
