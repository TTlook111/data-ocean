-- 取消意图与任务终态在同一行提交；ACCEPTED 仅表示 Python 已接收，不代表执行已停止。
-- 历史记录保持 NULL，不推断旧取消是否已投递，也不批量重发历史操作。
ALTER TABLE query_task
    ADD COLUMN cancellation_delivery_status VARCHAR(16) NULL COMMENT '取消投递状态：PENDING/ACCEPTED',
    ADD COLUMN cancellation_next_attempt_at DATETIME NULL COMMENT '取消投递下次可重试时间；不表示意图过期',
    ADD INDEX idx_query_cancellation_delivery (cancellation_delivery_status, cancellation_next_attempt_at, id);
