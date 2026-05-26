-- ============================================================
-- Prompt 管理模块 - 初始化 5 个核心模板
-- ============================================================

INSERT INTO prompt_template (template_code, template_name, scenario, content, current_version, enabled) VALUES
('sql_generation', 'SQL 生成模板', 'sql_generation',
'你是一个专业的 SQL 生成助手。根据用户的自然语言问题和提供的数据库 Schema 信息，生成准确的 MySQL SQL 查询语句。

## 规则
1. 只生成 SELECT 语句，禁止 INSERT/UPDATE/DELETE
2. 必须使用提供的表和字段，不要臆造
3. 优先使用可信度高的字段
4. 涉及时间的查询使用合适的时间函数
5. 结果集加 LIMIT 限制，默认不超过 1000 行

## Schema 信息
{{schema}}

## 字段可信度
{{field_confidence}}

## 用户问题
{{question}}

## 改写后的结构化查询
{{rewritten_query}}

请生成 SQL：', 1, 1),

('chart_generation', '图表生成模板', 'chart_generation',
'根据以下查询结果数据，生成合适的 ECharts 图表配置（JSON 格式）。

## 规则
1. 根据数据特征自动选择图表类型（柱状图/折线图/饼图）
2. 时间序列数据优先使用折线图
3. 分类对比数据使用柱状图
4. 占比数据使用饼图
5. 配置必须是合法的 ECharts option JSON

## 查询结果列
{{columns}}

## 查询结果数据（前 20 行）
{{data}}

## 用户原始问题
{{question}}

请生成 ECharts option JSON：', 1, 1),

('intent_recognition', '意图识别模板', 'intent_recognition',
'分析用户的自然语言问题，提取查询意图。

## 任务
1. 识别时间范围（相对时间转绝对时间）
2. 识别聚合方式（求和/计数/平均/最大/最小）
3. 识别筛选条件
4. 识别排序要求
5. 识别分组维度

## 当前时间
{{current_time}}

## 用户问题
{{question}}

## 上下文（最近对话）
{{context}}

请输出结构化的查询意图 JSON：', 1, 1),

('schema_retrieval_query', 'Schema 检索查询模板', 'schema_retrieval',
'根据用户问题生成用于向量检索的查询文本，用于从 Schema RAG 中召回相关的表和字段。

## 规则
1. 提取问题中的业务实体（如"订单"、"客户"、"产品"）
2. 提取度量指标（如"金额"、"数量"、"比率"）
3. 提取时间维度
4. 生成适合向量检索的关键词组合

## 用户问题
{{question}}

## 改写后的结构化查询
{{rewritten_query}}

请生成检索查询文本：', 1, 1),

('memory_extraction', '记忆提取模板', 'memory_extraction',
'从对话历史中提取需要记住的上下文信息，用于多轮对话的指代消解。

## 规则
1. 提取上一轮查询涉及的表和字段
2. 提取时间范围上下文
3. 提取筛选条件上下文
4. 不要提取无关的闲聊内容

## 对话历史
{{conversation_history}}

## 当前问题
{{question}}

请提取需要传递给下一轮的上下文 JSON：', 1, 1);

-- 为每个模板创建 v1 版本记录
INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, created_by)
SELECT id, 1, content, '初始版本', 1, NULL FROM prompt_template;
