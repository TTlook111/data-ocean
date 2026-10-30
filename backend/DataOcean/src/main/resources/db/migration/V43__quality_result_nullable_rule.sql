-- 质量检查结果时序表：rule_id 改为可为空（维度级结果无 rule_id）
ALTER TABLE quality_check_result MODIFY COLUMN rule_id BIGINT NULL COMMENT '规则 ID（维度级结果为空）';

-- 新增 total_score 字段，记录该次检查的加权总分
ALTER TABLE quality_check_result ADD COLUMN total_score DECIMAL(5,2) NULL COMMENT '加权总分' AFTER snapshot_id;
