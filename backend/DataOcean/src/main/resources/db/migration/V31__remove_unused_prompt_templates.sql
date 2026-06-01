-- ============================================================
-- 删除未接入 Agent 流程的 Prompt 模板
-- schema_retrieval_query: schema_retriever 不使用 LLM，无法接入
-- memory_extraction: 无对应 Agent 节点，需后续迭代
-- ============================================================

-- 先删除版本记录（外键关联）
DELETE FROM prompt_template_version
WHERE template_id IN (
    SELECT id FROM prompt_template
    WHERE template_code IN ('schema_retrieval_query', 'memory_extraction')
);

-- 再删除模板主体
DELETE FROM prompt_template
WHERE template_code IN ('schema_retrieval_query', 'memory_extraction');
