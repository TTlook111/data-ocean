-- 独立子项的补充说明只替换该项的申请资料。
ALTER TABLE access_request_item
    ADD COLUMN application_purpose VARCHAR(500) NULL,
    ADD COLUMN application_data_needed VARCHAR(2000) NULL,
    ADD COLUMN application_supplement VARCHAR(1000) NULL;

-- 经办关系指向该人员最近一次办理时保存的整单快照。
ALTER TABLE access_request_item_action
    ADD COLUMN decision_version INT UNSIGNED NULL;

UPDATE access_request_item_action a
JOIN (
    SELECT member.item_id, revision.actor_id, MAX(revision.version) AS decision_version
    FROM access_request_revision revision
    JOIN JSON_TABLE(revision.snapshot, '$.items[*]' COLUMNS (
        item_id BIGINT UNSIGNED PATH '$.id',
        handler_id BIGINT UNSIGNED PATH '$.handler.id'
    )) AS member
    WHERE revision.action IN ('APPROVE', 'REJECT', 'SUPPLEMENT')
      AND member.handler_id = revision.actor_id
    GROUP BY member.item_id, revision.actor_id
) AS recorded ON recorded.item_id = a.item_id AND recorded.actor_id = a.actor_id
SET a.decision_version = recorded.decision_version;
