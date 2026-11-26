-- 先保存待构建的单文档发布任务，候选及其固定输入在首次受理构建时关联。
CREATE TABLE kn_release_task (
    id BIGINT UNSIGNED PRIMARY KEY,
    scope_id BIGINT UNSIGNED NOT NULL,
    document_revision_id BIGINT UNSIGNED NOT NULL,
    candidate_id BIGINT UNSIGNED NULL,
    title VARCHAR(120) NOT NULL,
    canceled BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY ix_kn_release_task_scope (scope_id, id),
    UNIQUE KEY uk_kn_release_task_candidate (candidate_id),
    CONSTRAINT fk_kn_release_task_scope FOREIGN KEY (scope_id) REFERENCES kn_release_scope(id),
    CONSTRAINT fk_kn_release_task_document FOREIGN KEY (document_revision_id) REFERENCES kn_document_revision(id),
    CONSTRAINT fk_kn_release_task_candidate FOREIGN KEY (candidate_id) REFERENCES kn_release_candidate(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 本次调整前的记录已经冻结，保留旧任务链接、候选、构建和审计编号。
INSERT INTO kn_release_task (id, scope_id, document_revision_id, candidate_id, title, created_by, created_at)
SELECT id, scope_id, document_revision_id, id, title, created_by, created_at FROM kn_release_candidate;
