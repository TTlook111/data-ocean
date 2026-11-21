-- 文档间的必要引用属于正文修订，与共享治理事实分开。
CREATE TABLE kn_document_dependency (
    revision_id BIGINT UNSIGNED NOT NULL,
    required_document_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (revision_id,required_document_id),
    KEY ix_kn_document_dependency_reverse (required_document_id,revision_id),
    CONSTRAINT fk_kn_doc_dependency_revision FOREIGN KEY (revision_id) REFERENCES kn_document_revision(id),
    CONSTRAINT fk_kn_doc_dependency_target FOREIGN KEY (required_document_id) REFERENCES kn_document(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 正式引用记录发布时实际核对的目标绑定，原依据可追溯。
CREATE TABLE kn_binding_dependency (
    binding_id BIGINT UNSIGNED NOT NULL,
    required_binding_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (binding_id,required_binding_id),
    CONSTRAINT fk_kn_binding_dependency_owner FOREIGN KEY (binding_id) REFERENCES kn_release_binding(id),
    CONSTRAINT fk_kn_binding_dependency_target FOREIGN KEY (required_binding_id) REFERENCES kn_release_binding(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 撤下改变绑定的使用资格，完整清单和成员留痕独立于版本正文。
CREATE TABLE kn_release_withdrawal (
    id BIGINT UNSIGNED PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    target_binding_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(300) NOT NULL,
    withdrawn_by BIGINT UNSIGNED NOT NULL,
    withdrawn_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    UNIQUE KEY uk_kn_withdrawal_command (withdrawn_by,command_id),
    CONSTRAINT fk_kn_withdrawal_target FOREIGN KEY (target_binding_id) REFERENCES kn_release_binding(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE kn_release_withdrawal_member (
    binding_id BIGINT UNSIGNED PRIMARY KEY,
    withdrawal_id BIGINT UNSIGNED NOT NULL,
    depth INT UNSIGNED NOT NULL,
    KEY ix_kn_withdrawal_batch (withdrawal_id,depth,binding_id),
    CONSTRAINT fk_kn_withdrawal_member_binding FOREIGN KEY (binding_id) REFERENCES kn_release_binding(id),
    CONSTRAINT fk_kn_withdrawal_member_batch FOREIGN KEY (withdrawal_id) REFERENCES kn_release_withdrawal(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 治理复核依据不改写，独立保存完成知识处理的批次和时间。
ALTER TABLE gov_impact_task
    ADD COLUMN knowledge_withdrawal_id BIGINT UNSIGNED NULL,
    ADD COLUMN knowledge_completed_at DATETIME(3) NULL;
