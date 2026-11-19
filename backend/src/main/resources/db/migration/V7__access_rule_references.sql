-- 当前规则引用以受外键约束的关系保存；历史正文仍由原审批或配置快照保留。
CREATE TABLE access_item_rule (
    item_id BIGINT UNSIGNED NOT NULL,
    rule_id BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    PRIMARY KEY (item_id,rule_id),
    UNIQUE KEY uk_access_item_rule_order (item_id,position),
    KEY ix_access_item_rule_ref (rule_id,item_id),
    CONSTRAINT fk_access_item_rule_item FOREIGN KEY (item_id) REFERENCES access_request_item(id),
    CONSTRAINT fk_access_item_rule_rule FOREIGN KEY (rule_id) REFERENCES access_output_rule(id)
) ENGINE=InnoDB;

CREATE TABLE access_department_rule (
    configuration_id BIGINT UNSIGNED NOT NULL,
    rule_id BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL,
    PRIMARY KEY (configuration_id,rule_id),
    UNIQUE KEY uk_access_department_rule_order (configuration_id,position),
    KEY ix_access_department_rule_ref (rule_id,configuration_id),
    CONSTRAINT fk_access_dept_rule_config FOREIGN KEY (configuration_id) REFERENCES access_department_source(id),
    CONSTRAINT fk_access_dept_rule_rule FOREIGN KEY (rule_id) REFERENCES access_output_rule(id)
) ENGINE=InnoDB;

INSERT INTO access_item_rule(item_id,rule_id,position)
SELECT i.id, item_rule.rule_id, item_rule.position
FROM access_request_item i
JOIN JSON_TABLE(COALESCE(i.rule_ids, JSON_ARRAY()), '$[*]'
    COLUMNS (position FOR ORDINALITY, rule_id BIGINT UNSIGNED PATH '$' ERROR ON EMPTY ERROR ON ERROR)
) item_rule;

INSERT INTO access_department_rule(configuration_id,rule_id,position)
SELECT c.id, dept_rule.rule_id, dept_rule.position
FROM access_department_source c
JOIN JSON_TABLE(c.rule_ids, '$[*]'
    COLUMNS (position FOR ORDINALITY, rule_id BIGINT UNSIGNED PATH '$' ERROR ON EMPTY ERROR ON ERROR)
) dept_rule;

ALTER TABLE access_request_item DROP COLUMN rule_ids;
ALTER TABLE access_department_source DROP COLUMN rule_ids;
