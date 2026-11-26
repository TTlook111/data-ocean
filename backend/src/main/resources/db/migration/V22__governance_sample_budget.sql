-- 每个字段任务固定接收时的有效渐进额度，历史任务默认保持原无采样行为。
ALTER TABLE gov_generation_task ADD COLUMN sample_row_limits JSON NULL;
