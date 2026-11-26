-- 业务对话按本人和数据源隔离。已有轮次按原请求各自保留。
CREATE TABLE qry_conversation (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    latest_request_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    KEY idx_qry_conversation_history (user_id, source_id, updated_at)
);

ALTER TABLE qry_request
    ADD COLUMN conversation_id VARCHAR(36) NULL,
    ADD COLUMN inherit_context BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN run_version INT NOT NULL DEFAULT 0,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD COLUMN run_input JSON NULL;

INSERT INTO qry_conversation(id,user_id,source_id,title,latest_request_id,created_at,updated_at)
SELECT id,user_id,source_id,LEFT(question,120),id,created_at,created_at FROM qry_request;

UPDATE qry_request SET conversation_id=id WHERE conversation_id IS NULL;

ALTER TABLE qry_request
    MODIFY conversation_id VARCHAR(36) NOT NULL,
    ADD KEY idx_qry_conversation_turns (conversation_id, created_at),
    ADD KEY idx_qry_lease (state, lease_until);

-- 解释独立于已交付结果，保存每次解释和幂等操作的实际修订。
CREATE TABLE qry_explanation (
    request_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    command_id VARCHAR(36) NOT NULL,
    status VARCHAR(24) NOT NULL,
    text VARCHAR(1200) NULL,
    model_revision_id BIGINT NULL,
    failure_code VARCHAR(32) NULL,
    lease_until DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (request_id, revision),
    UNIQUE KEY uq_qry_explanation_command (request_id,command_id),
    KEY idx_qry_explanation_dispatch (status,created_at)
);

-- 先登记外发动作，再由 Python 检查点固化回执。缺失回执不得盲目重发。
CREATE TABLE qry_ai_step (
    request_id VARCHAR(36) NOT NULL,
    step VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    PRIMARY KEY (request_id, step)
);
