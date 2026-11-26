-- 技术修复等待生效时与源数据等待共用在办约束，已提交结果不能同时处于等待。
ALTER TABLE fb_issue_task
    DROP CHECK ck_fb_task_waiting,
    ADD CONSTRAINT ck_fb_task_waiting CHECK (waiting_external=FALSE
        OR (direction IN ('TECHNICAL','SOURCE_DATA') AND status='IN_PROGRESS'));

-- 跟进继续保存已做工作与下一步，保留原处理结果及历史事实。
ALTER TABLE fb_issue_activity
    DROP CHECK ck_fb_activity_kind,
    DROP CHECK ck_fb_activity_root,
    ADD CONSTRAINT ck_fb_activity_kind CHECK (action IN (
        'ASSIGNED','REVIEWED','RESULT_SUBMITTED','VERIFIED','RECHECK_FAILED','CLOSED',
        'TASK_ADDED','TASK_ASSIGNED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED','TASK_RESTORED',
        'SOURCE_FOLLOW_UP','SOURCE_WAITING','TECHNICAL_FOLLOW_UP','TECHNICAL_WAITING',
        'SUPPLEMENT_ASKED','SUPPLEMENT_ADDED','SUPPLEMENT_ENDED',
        'MERGED','MEMBER_ADDED','UNMERGED','MEMBER_REMOVED','REOPENED'
    )),
    ADD CONSTRAINT ck_fb_activity_root CHECK (
        action IN ('REVIEWED','TASK_REVIEWED','TASK_NO_CHANGE','TASK_CANCELLED',
            'SOURCE_FOLLOW_UP','SOURCE_WAITING','TECHNICAL_FOLLOW_UP','TECHNICAL_WAITING')
        OR root_cause IS NULL
    );
