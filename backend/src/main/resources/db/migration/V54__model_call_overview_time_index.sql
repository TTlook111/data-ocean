-- 模型运行概览及调用明细共用真实回执时间索引。
ALTER TABLE gov_model_call ADD KEY ix_gov_model_call_recorded_at (recorded_at, id);
