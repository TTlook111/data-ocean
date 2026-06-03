-- 为查询任务表添加降级标记字段
ALTER TABLE query_task ADD COLUMN degraded TINYINT(1) DEFAULT 0 COMMENT '是否使用了降级方案' AFTER masked_fields;
ALTER TABLE query_task ADD COLUMN degrade_notice VARCHAR(256) DEFAULT NULL COMMENT '降级提示信息' AFTER degraded;

-- AI 服务配置预置数据
INSERT INTO sys_config (config_key, config_value, description) VALUES
('ai.dashscope.apiKey', '', 'DashScope API Key（AES 加密存储）'),
('ai.dashscope.baseUrl', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 'DashScope API 端点'),
('ai.llm.model', 'qwen-plus', 'LLM 模型名称'),
('ai.llm.temperature', '0.3', 'LLM 温度参数（0-1）'),
('ai.llm.timeout', '120', 'LLM 调用超时（秒）'),
('ai.embedding.model', 'text-embedding-v4', 'Embedding 模型名称'),
('ai.embedding.dimension', '1024', 'Embedding 向量维度');
