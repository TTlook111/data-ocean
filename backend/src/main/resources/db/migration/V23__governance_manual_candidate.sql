-- 手工治理候选由操作者发起，无需绑定 AI 发现任务；既有关联继续受外键保护。
ALTER TABLE gov_discovery_candidate
    MODIFY request_id CHAR(36) CHARACTER SET ascii NULL;
