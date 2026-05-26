-- ============================================================
-- 字段 Tag 与可信度模块 - 预定义标签初始化
-- 初始化系统预定义的标签数据
-- ============================================================

-- 预定义标签参考表（用于前端下拉和校验）
CREATE TABLE IF NOT EXISTS predefined_tag (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    tag_code    VARCHAR(50)  NOT NULL COMMENT '标签编码',
    tag_name    VARCHAR(100) NOT NULL COMMENT '标签显示名称',
    category    VARCHAR(50)  NOT NULL COMMENT '标签分类',
    description VARCHAR(200) NULL COMMENT '标签说明',
    sort_order  INT          NOT NULL DEFAULT 0 COMMENT '排序序号',
    UNIQUE INDEX uk_predefined_tag_code (tag_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预定义标签参考表';

-- 插入预定义标签数据
INSERT INTO predefined_tag (tag_code, tag_name, category, description, sort_order) VALUES
('AMOUNT',      '金额类',   '业务类型', '表示金额、价格、费用等货币相关字段', 1),
('TIME',        '时间类',   '业务类型', '表示日期、时间、时间戳等时间相关字段', 2),
('STATUS',      '状态类',   '业务类型', '表示状态、标志位等枚举类字段', 3),
('USER_ID',     '用户ID类', '业务类型', '表示用户标识、账号ID等关联字段', 4),
('SENSITIVE',   '敏感',     '安全标记', '包含敏感信息，查询时需脱敏处理', 10),
('DEPRECATED',  '废弃',     '生命周期', '已废弃字段，不应出现在新查询中', 20),
('RECOMMENDED', '推荐',     '生命周期', '推荐使用的高质量字段', 21),
('BLOCKED',     '阻断',     '生命周期', '被阻断的字段，禁止在查询中使用', 22);
