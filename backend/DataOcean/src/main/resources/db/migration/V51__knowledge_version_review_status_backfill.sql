-- =====================================================
-- V51: 回填 knowledge_doc_version.review_status 并修正列注释
--
-- 背景：该列自 V13 建表起定义为 NOT NULL DEFAULT 'PENDING'，但
-- KnowledgeVersionServiceImpl.createVersion 从不写入它，approve / reject 也只更新
-- knowledge_doc 表。因此该列恒为 'PENDING'、reviewer_id 恒为 NULL，
-- 任何按它展示审核状态的地方都会得到「恒为待审核」的错误结论（含已发布的版本）。
--
-- 本次改动让 approve / reject 同步写入版本行。历史数据的处置：
--   1. 能从 knowledge_review_task 还原的，按其最新一条审核任务回填；
--   2. 还原不了的，标为 'UNKNOWN'，而不是继续谎称 'PENDING'。
--
-- 判据：reviewer_id IS NULL 表示该行从未被新逻辑写入过。迁移时刻执行时，
-- 这恰好覆盖全部历史行；此后新产生的版本行由应用显式写入，不会被本脚本影响。
-- =====================================================

UPDATE knowledge_doc_version v
SET v.review_status = COALESCE(
        (SELECT t.review_status
         FROM knowledge_review_task t
         WHERE t.doc_version_id = v.id
         ORDER BY t.id DESC
         LIMIT 1),
        'UNKNOWN'),
    v.reviewer_id = (
        SELECT t.reviewer_id
        FROM knowledge_review_task t
        WHERE t.doc_version_id = v.id
        ORDER BY t.id DESC
        LIMIT 1)
WHERE v.reviewer_id IS NULL;

ALTER TABLE knowledge_doc_version
    MODIFY COLUMN review_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
        COMMENT '审核状态: UNKNOWN(历史未记录)/PENDING/APPROVED/REJECTED';
