-- 治理依据由人工确认并绑定精确修订；反馈修复需独立验收；后台执行使用领取令牌。
ALTER TABLE business_knowledge_revision ADD COLUMN evidence_json JSON NULL COMMENT '人工依据引用、摘录和当前内容来源确认';
ALTER TABLE governance_feedback ADD COLUMN resolution_json JSON NULL COMMENT '原问题、验收标准和人工修复验证记录';
ALTER TABLE governance_task ADD COLUMN execution_token VARCHAR(64) NULL COMMENT '当前领取令牌，过期执行不得回写';
ALTER TABLE governance_task ADD COLUMN round_no INT NOT NULL DEFAULT 1 COMMENT '对象治理轮次';
ALTER TABLE governance_confidence_event ADD COLUMN evidence_policy_version INT NOT NULL DEFAULT 1 COMMENT '证据去重政策版本，历史不自动重算为新证据';
