-- 固定术语与人工释义的审核来源；历史任务不补造来源证明。
ALTER TABLE query_task
    ADD COLUMN reviewed_knowledge_refs_json JSON NULL COMMENT '派发时固定的术语/人工释义审核引用';
