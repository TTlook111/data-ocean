-- 源数据任务由平台人员跟进，等待外部修正或同步时仍属于必要任务。
ALTER TABLE fb_issue_task
    DROP CHECK ck_fb_task_direction,
    ADD COLUMN waiting_external BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_fb_task_direction CHECK (direction IN ('KNOWLEDGE','TECHNICAL','SOURCE_DATA')),
    ADD CONSTRAINT ck_fb_task_waiting CHECK (waiting_external=FALSE
        OR (direction='SOURCE_DATA' AND status='IN_PROGRESS'));

-- 实际修正人或团队独立于记录人，修正结果继续保存为不可变版本。
ALTER TABLE fb_issue_result
    DROP CHECK ck_fb_result_impact,
    ADD COLUMN source_repairer VARCHAR(120) NULL,
    ADD CONSTRAINT ck_fb_result_impact CHECK (
        (impact IN ('DISPLAY','CONNECTION','QUERY_SAFETY') AND knowledge_binding_id IS NULL AND source_repairer IS NULL)
        OR (impact='KNOWLEDGE' AND knowledge_binding_id IS NOT NULL AND source_repairer IS NULL)
        OR (impact='SOURCE_DATA' AND knowledge_binding_id IS NULL AND source_repairer IS NOT NULL)
    );

-- 跟进与完成分别留痕，跟进记录保存实际工作及下一步。
ALTER TABLE fb_issue_activity
    DROP CHECK ck_fb_activity_kind,
    DROP CHECK ck_fb_activity_root,
    ADD CONSTRAINT ck_fb_activity_kind CHECK (action IN (
        'ASSIGNED','REVIEWED','RESULT_SUBMITTED','VERIFIED','RECHECK_FAILED','CLOSED',
        'TASK_ADDED','TASK_ASSIGNED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED','TASK_RESTORED',
        'SOURCE_FOLLOW_UP','SOURCE_WAITING'
    )),
    ADD CONSTRAINT ck_fb_activity_root CHECK (
        action IN ('REVIEWED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED','SOURCE_FOLLOW_UP','SOURCE_WAITING')
        OR root_cause IS NULL
    );

-- 之前分流为源数据但没有任务的未结事项沿用原负责人及当期核查。
INSERT INTO fb_issue_task(issue_id,task_id,direction,assignee_id,status,reviewed)
SELECT i.id,1,'SOURCE_DATA',i.assignee_id,'IN_PROGRESS',
    EXISTS(SELECT 1 FROM fb_issue_activity a WHERE a.issue_id=i.id AND a.action='REVIEWED'
        AND a.actor_id=i.assignee_id AND a.issue_version>COALESCE(
            (SELECT MAX(b.issue_version) FROM fb_issue_activity b WHERE b.issue_id=i.id AND b.action='ASSIGNED'),0))
FROM fb_issue i
WHERE i.direction='SOURCE_DATA' AND i.status='IN_PROGRESS' AND i.assignee_id IS NOT NULL
    AND NOT EXISTS(SELECT 1 FROM fb_issue_task t WHERE t.issue_id=i.id);
