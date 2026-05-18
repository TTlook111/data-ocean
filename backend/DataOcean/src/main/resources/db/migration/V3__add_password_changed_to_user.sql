ALTER TABLE sys_user
    ADD COLUMN password_changed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已修改初始密码' AFTER password_hash;
