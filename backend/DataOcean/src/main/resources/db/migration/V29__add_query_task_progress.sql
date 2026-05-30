-- V29: 为 query_task 添加实时进度字段
-- 供 Java 消费 Python SSE progress 事件后实时回写，前端轮询读取展示分阶段进度
ALTER TABLE query_task ADD COLUMN progress_node VARCHAR(64) NULL COMMENT '当前执行节点（QUERY_REWRITER/SCHEMA_RETRIEVER/SQL_GENERATOR/SQL_VALIDATOR/SQL_EXECUTOR/DATA_VISUALIZER）' AFTER status;
ALTER TABLE query_task ADD COLUMN progress_message VARCHAR(255) NULL COMMENT '当前进度提示文案（如“正在生成 SQL”）' AFTER progress_node;
