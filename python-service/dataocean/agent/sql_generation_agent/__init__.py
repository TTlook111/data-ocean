"""SQL Generator Agent — 受控的 LangChain create_agent 子循环

将 SQL_Generator 节点内部从一次性 LLM 调用升级为
基于只读工具的 Agent 子循环，生成更准确的 SQL 草稿。
"""
