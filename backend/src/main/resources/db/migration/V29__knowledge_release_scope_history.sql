-- 保存任务版本基线，换范围时任务版本随原记录递增。
ALTER TABLE kn_release_task ADD COLUMN base_version INT UNSIGNED NOT NULL DEFAULT 1;
UPDATE kn_release_task SET base_version=2 WHERE canceled=TRUE AND candidate_id IS NULL;

-- 候选标明归属任务，换范围只替换当前指针，旧候选和构建保持原样。
ALTER TABLE kn_release_candidate ADD COLUMN task_id BIGINT UNSIGNED NULL;
UPDATE kn_release_candidate c JOIN kn_release_task t ON t.candidate_id=c.id
SET c.task_id=t.id;
ALTER TABLE kn_release_candidate
    MODIFY COLUMN task_id BIGINT UNSIGNED NOT NULL,
    ADD KEY ix_kn_release_candidate_task (task_id, id),
    ADD CONSTRAINT fk_kn_release_candidate_task FOREIGN KEY (task_id) REFERENCES kn_release_task(id);
