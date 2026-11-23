-- 接入概览常按结束时间读取全源任务，保留可用的时间范围索引。
ALTER TABLE ds_collection_task ADD KEY ix_ds_task_end_source (ended_at,source_id);
