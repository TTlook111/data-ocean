-- 发布批次及成员固定原来的完整切换范围。
CREATE TABLE kn_publication_batch (
    id BIGINT UNSIGNED PRIMARY KEY,
    source_id BIGINT UNSIGNED NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    verified_at DATETIME(3) NULL,
    UNIQUE KEY uk_kn_batch_command (actor_id,command_id),
    KEY ix_kn_batch_source (source_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE kn_publication_batch_member (
    binding_id BIGINT UNSIGNED PRIMARY KEY,
    batch_id BIGINT UNSIGNED NOT NULL,
    KEY ix_kn_batch_members (batch_id,binding_id),
    CONSTRAINT fk_kn_batch_member_batch FOREIGN KEY (batch_id) REFERENCES kn_publication_batch(id),
    CONSTRAINT fk_kn_batch_member_binding FOREIGN KEY (binding_id) REFERENCES kn_release_binding(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 已有正式记录各自是原来的单文档批次，保留历史基础和核实状态。
INSERT INTO kn_publication_batch(id,source_id,actor_id,command_id,created_at,verified_at)
SELECT b.id,s.source_id,b.published_by,b.command_id,b.created_at,b.verified_at
FROM kn_release_binding b JOIN kn_release_scope s ON s.id=b.scope_id;

INSERT INTO kn_publication_batch_member(binding_id,batch_id)
SELECT id,id FROM kn_release_binding;

-- 回退只改变当前入口，原发布和问题版本仍作为审计事实保留。
CREATE TABLE kn_publication_rollback (
    id BIGINT UNSIGNED PRIMARY KEY,
    batch_id BIGINT UNSIGNED NOT NULL,
    actor_id BIGINT UNSIGNED NOT NULL,
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(300) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    verified_at DATETIME(3) NULL,
    UNIQUE KEY uk_kn_rollback_batch (batch_id),
    UNIQUE KEY uk_kn_rollback_command (actor_id,command_id),
    CONSTRAINT fk_kn_rollback_batch FOREIGN KEY (batch_id) REFERENCES kn_publication_batch(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 同一入口再次出现亦区别于过去的基础，旧候选不能借回退重新变有效。
ALTER TABLE kn_release_scope ADD COLUMN binding_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0;
ALTER TABLE kn_release_candidate ADD COLUMN base_binding_epoch BIGINT UNSIGNED NOT NULL DEFAULT 0;
