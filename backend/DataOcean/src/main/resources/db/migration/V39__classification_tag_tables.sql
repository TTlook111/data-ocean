-- V39: 分类标签体系 + 数据质量深化
-- 建立两级标签体系（Classification → Tag），深化数据质量规则。

-- ========== 分类标签体系 ==========

-- 标签分类表（一级：PII、数据分级、业务域）
CREATE TABLE IF NOT EXISTS classification (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(64) NOT NULL COMMENT '分类名称',
    description     VARCHAR(512) COMMENT '分类描述',
    is_system       BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否系统内置',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_classification_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签分类表';

-- 标签表（二级：身份证号、手机号、公开、内部等）
CREATE TABLE IF NOT EXISTS tag (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    classification_id BIGINT NOT NULL COMMENT '所属分类 ID',
    name            VARCHAR(64) NOT NULL COMMENT '标签名称',
    display_name    VARCHAR(128) COMMENT '显示名称',
    description     VARCHAR(512) COMMENT '标签描述',
    fqn             VARCHAR(256) NOT NULL COMMENT '全限定名：分类名.标签名',
    style           JSON COMMENT '样式配置（颜色、图标等）',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_tag_fqn (fqn),
    UNIQUE KEY uk_tag (classification_id, name),
    INDEX idx_tag_classification (classification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签表';

-- 预置分类数据
INSERT INTO classification (name, description, is_system) VALUES
('PII', '个人身份信息标签', TRUE),
('数据分级', '数据安全分级标签', TRUE),
('业务域', '业务领域标签', TRUE);

-- 预置标签数据
INSERT INTO tag (classification_id, name, display_name, description, fqn) VALUES
-- PII 分类
((SELECT id FROM classification WHERE name = 'PII'), '身份证号', '身份证号', '居民身份证号码', 'PII.身份证号'),
((SELECT id FROM classification WHERE name = 'PII'), '手机号', '手机号', '移动电话号码', 'PII.手机号'),
((SELECT id FROM classification WHERE name = 'PII'), '姓名', '姓名', '真实姓名', 'PII.姓名'),
((SELECT id FROM classification WHERE name = 'PII'), '邮箱', '邮箱', '电子邮箱地址', 'PII.邮箱'),
((SELECT id FROM classification WHERE name = 'PII'), '银行卡号', '银行卡号', '银行卡号', 'PII.银行卡号'),
((SELECT id FROM classification WHERE name = 'PII'), '地址', '地址', '家庭或通讯地址', 'PII.地址'),
-- 数据分级分类
((SELECT id FROM classification WHERE name = '数据分级'), '公开', '公开', '可公开的数据', '数据分级.公开'),
((SELECT id FROM classification WHERE name = '数据分级'), '内部', '内部', '内部使用数据', '数据分级.内部'),
((SELECT id FROM classification WHERE name = '数据分级'), '机密', '机密', '机密数据', '数据分级.机密'),
((SELECT id FROM classification WHERE name = '数据分级'), '绝密', '绝密', '绝密数据', '数据分级.绝密'),
-- 业务域分类
((SELECT id FROM classification WHERE name = '业务域'), '销售', '销售', '销售业务域', '业务域.销售'),
((SELECT id FROM classification WHERE name = '业务域'), '财务', '财务', '财务业务域', '业务域.财务'),
((SELECT id FROM classification WHERE name = '业务域'), '人力', '人力', '人力资源业务域', '业务域.人力'),
((SELECT id FROM classification WHERE name = '业务域'), '供应链', '供应链', '供应链业务域', '业务域.供应链');

-- ========== 数据质量深化 ==========

-- 扩展 metadata_quality_rule 表（新增 check_type、check_expression、threshold 字段）
ALTER TABLE metadata_quality_rule
    ADD COLUMN check_type VARCHAR(32) NOT NULL DEFAULT 'SCHEMA' COMMENT '检查类型：SCHEMA / DATA' AFTER check_target,
    ADD COLUMN check_expression TEXT COMMENT '检查表达式（DATA 类型规则使用）' AFTER check_type,
    ADD COLUMN threshold DECIMAL(10,4) COMMENT '阈值（DATA 类型规则使用）' AFTER check_expression;

-- 新增数据级质量规则
INSERT INTO metadata_quality_rule (rule_code, rule_name, dimension, severity, description, check_target, check_type, check_expression, threshold, deduction_points) VALUES
('DATA_NULL_RATE_HIGH', '列空值率过高', 'COMPLETENESS', 'MEDIUM', '检测列空值率是否超过阈值', 'COLUMN', 'DATA', 'null_rate > threshold', 0.3000, 3.0),
('DATA_UNIQUE_VIOLATION', '应唯一列出现重复值', 'ACCURACY', 'HIGH', '检测应唯一列是否出现重复值', 'COLUMN', 'DATA', 'distinct_count < row_count', NULL, 5.0),
('DATA_FK_ORPHAN', '外键引用目标不存在', 'CONSISTENCY', 'HIGH', '检测外键引用的目标记录是否存在', 'RELATION', 'DATA', 'fk_orphan_count > 0', NULL, 5.0),
('DATA_STALE_TABLE', '表数据长期未更新', 'TIMELINESS', 'LOW', '检测表数据是否超过配置天数未更新', 'TABLE', 'DATA', 'days_since_update > threshold', 30.0000, 2.0);

-- 质量趋势时序表
CREATE TABLE IF NOT EXISTS quality_check_result (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id     BIGINT NOT NULL COMMENT '快照 ID',
    rule_id         BIGINT NOT NULL COMMENT '规则 ID',
    dimension       VARCHAR(32) NOT NULL COMMENT '校验维度',
    score           DECIMAL(5,2) NOT NULL COMMENT '得分',
    issue_count     INT NOT NULL DEFAULT 0 COMMENT '问题数量',
    checked_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '检查时间',
    INDEX idx_qcr_snapshot (snapshot_id),
    INDEX idx_qcr_time (checked_at),
    INDEX idx_qcr_rule (rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量检查结果时序表';
