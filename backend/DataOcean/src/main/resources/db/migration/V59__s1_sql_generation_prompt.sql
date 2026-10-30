-- IAM-SIMPLE-1：把 sql_generation 模板改写为 S1 查询链路使用的变量契约
--
-- 背景：该模板原本服务旧 Agent 的 SQL 生成节点，变量是 schema / field_confidence /
-- conversation_summary / question / rewritten_query。B6 删除旧问数链路后它没有任何消费方。
-- S1 查询链路（/internal/iam-s1/query）此前把提示词写死在 Python 代码里，绕过了受管模板，
-- 使管理员无法在 Prompt 策略页调整 SQL 生成行为。本次把 S1 接回受管模板。
--
-- 变量契约（S1 实际提供）：question / schema / rag / glossary / few_shot / history / summary。
-- 旧模板引用的 field_confidence 与 rewritten_query 在 S1 链路中不存在，沿用旧内容会让这两段
-- 静默渲染为空，因此必须一并改写。
--
-- 安全边界：模板只承载业务与格式约定。权限执行边界在 Context Firewall 与 SQL AST 校验器，
-- 不在提示词；身份与"只生成 SELECT、只用给定表字段"等安全框架保留在 Python 代码中，
-- 不随本模板交给管理员编辑。

INSERT INTO prompt_template_version (template_id, version_no, content, change_summary, is_active, status, created_by)
SELECT id, current_version + 1,
'你是一个专业的 MySQL SQL 生成助手。根据用户的自然语言问题与下面提供的 Schema 信息，生成一条准确的 MySQL SELECT 语句。

## 规则
1. 只生成 SELECT 语句，禁止 INSERT/UPDATE/DELETE
2. 只能使用下面提供的表和字段，不得臆造表名、字段名或 JOIN 关系
3. 优先使用有注释说明、可信度高的字段
4. 涉及时间的查询使用合适的时间函数，不要凭空假设时间范围
5. 不要自己添加 LIMIT，平台会按安全策略统一限制返回行数
6. 聚合或计算类输出必须用 AS 给出中文业务别名，让结果列对业务人员可读
   （例如 COUNT(*) AS 订单数、SUM(amount) AS 金额合计），不要留成 COUNT(*) 这样的表达式原文
7. 只输出 SQL 本身：不要解释、不要 Markdown 代码块标记、不要结尾分号

## 数据表与字段
{{schema}}

## 相关知识（检索命中）
{{rag}}

## 业务术语
{{glossary}}

## 相似问答示例
{{few_shot}}

## 会话上下文摘要
{{summary}}

## 最近对话
{{history}}

## 用户问题
{{question}}

请生成 SQL：',
'改为 IAM-SIMPLE-1 查询链路变量契约，并要求聚合输出使用中文业务别名', 1, 'APPROVED', NULL
FROM prompt_template WHERE template_code = 'sql_generation';

-- 旧版本转为历史，只保留新版本为 active
UPDATE prompt_template_version SET is_active = 0
WHERE template_id = (SELECT id FROM prompt_template WHERE template_code = 'sql_generation')
  AND version_no <= (SELECT current_version FROM prompt_template WHERE template_code = 'sql_generation');

UPDATE prompt_template
SET content = (SELECT content FROM prompt_template_version
               WHERE template_id = prompt_template.id
                 AND version_no = prompt_template.current_version + 1),
    current_version = current_version + 1,
    updated_at = NOW()
WHERE template_code = 'sql_generation';
