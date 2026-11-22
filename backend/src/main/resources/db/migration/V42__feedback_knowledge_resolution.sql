-- 知识治理结果绑定正式版本，复验必须引用从原问题派发的真实问数。
ALTER TABLE qry_request
    ADD COLUMN recheck_origin_id VARCHAR(36) NULL,
    ADD CONSTRAINT fk_qry_recheck_origin FOREIGN KEY (recheck_origin_id) REFERENCES qry_request(id);

ALTER TABLE fb_issue_result
    DROP CHECK ck_fb_result_impact,
    ADD COLUMN knowledge_binding_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_fb_result_binding FOREIGN KEY (knowledge_binding_id) REFERENCES kn_release_binding(id),
    ADD CONSTRAINT ck_fb_result_impact CHECK (
        (impact IN ('DISPLAY','CONNECTION','QUERY_SAFETY') AND knowledge_binding_id IS NULL)
        OR (impact='KNOWLEDGE' AND knowledge_binding_id IS NOT NULL)
    );

ALTER TABLE fb_issue_verification
    ADD COLUMN recheck_request_id VARCHAR(36) NULL,
    ADD CONSTRAINT fk_fb_verify_recheck FOREIGN KEY (recheck_request_id) REFERENCES qry_request(id);
