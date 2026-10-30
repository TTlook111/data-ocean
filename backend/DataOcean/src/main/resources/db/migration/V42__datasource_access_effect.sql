-- V42: make data source grants explicit allow/deny decisions.

ALTER TABLE datasource_access
    ADD COLUMN access_effect VARCHAR(16) NOT NULL DEFAULT 'ALLOW'
        COMMENT 'Grant effect: ALLOW or DENY' AFTER can_view_sql;

UPDATE datasource_access
SET access_effect = 'ALLOW'
WHERE access_effect IS NULL OR access_effect = '';

