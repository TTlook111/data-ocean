-- 审计使用数据库写入时的 UTC 毫秒时刻，适用于 MyBatis-Plus 生成的插入语句。
ALTER TABLE audit_record
    ALTER COLUMN occurred_at SET DEFAULT (UTC_TIMESTAMP(3));
