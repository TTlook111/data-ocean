-- DataOcean 轨道 A 真实业务验收夹具
--
-- 目的：为“创建数据源 -> 采集 -> 快照 -> 治理 -> 发布 -> 知识 -> 可问数”
-- 提供可复现的多表业务库。该脚本不修改 dataocean 管理库。
-- 执行用户：MySQL root（仅本机验收使用）

CREATE DATABASE IF NOT EXISTS dataocean_acceptance
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'dataocean_demo'@'%' IDENTIFIED BY 'DataOceanDemo@123';
ALTER USER 'dataocean_demo'@'%' IDENTIFIED BY 'DataOceanDemo@123';
GRANT SELECT, SHOW VIEW ON dataocean_acceptance.* TO 'dataocean_demo'@'%';
FLUSH PRIVILEGES;

USE dataocean_acceptance;

CREATE TABLE IF NOT EXISTS demo_customer (
    id BIGINT PRIMARY KEY,
    customer_code VARCHAR(32) NOT NULL UNIQUE,
    customer_name VARCHAR(100) NOT NULL,
    region VARCHAR(50) NOT NULL,
    customer_level VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_customer_region (region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示客户';

CREATE TABLE IF NOT EXISTS demo_product (
    id BIGINT PRIMARY KEY,
    product_code VARCHAR(32) NOT NULL UNIQUE,
    product_name VARCHAR(100) NOT NULL,
    category VARCHAR(50) NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    active TINYINT NOT NULL DEFAULT 1,
    INDEX idx_product_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示商品';

CREATE TABLE IF NOT EXISTS demo_order (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    customer_id BIGINT NOT NULL,
    order_date DATE NOT NULL,
    order_status VARCHAR(20) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    INDEX idx_order_customer (customer_id),
    INDEX idx_order_date (order_date),
    INDEX idx_order_status (order_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单';

CREATE TABLE IF NOT EXISTS demo_order_item (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    INDEX idx_order_item_order (order_id),
    INDEX idx_order_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示订单明细';

INSERT IGNORE INTO demo_customer
    (id, customer_code, customer_name, region, customer_level, created_at)
VALUES
    (1, 'CUST-001', '华北智造有限公司', '华北', 'A', '2025-11-03 09:00:00'),
    (2, 'CUST-002', '江南零售集团', '华东', 'A', '2025-11-10 10:30:00'),
    (3, 'CUST-003', '岭南餐饮连锁', '华南', 'B', '2025-12-01 14:20:00'),
    (4, 'CUST-004', '西部物流科技', '西南', 'B', '2025-12-15 11:45:00'),
    (5, 'CUST-005', '东北能源服务', '东北', 'C', '2026-01-06 08:40:00'),
    (6, 'CUST-006', '中原医疗器械', '华中', 'A', '2026-01-18 16:10:00');

INSERT IGNORE INTO demo_product
    (id, product_code, product_name, category, unit_price, active)
VALUES
    (1, 'PROD-001', '智能工业传感器', '工业设备', 899.00, 1),
    (2, 'PROD-002', '仓储管理终端', '软件服务', 2499.00, 1),
    (3, 'PROD-003', '冷链温控模块', '工业设备', 680.00, 1),
    (4, 'PROD-004', '门店分析服务', '软件服务', 3680.00, 1),
    (5, 'PROD-005', '配送路线优化包', '软件服务', 1980.00, 1),
    (6, 'PROD-006', '医疗扫码设备', '医疗设备', 1580.00, 1);

INSERT IGNORE INTO demo_order
    (id, order_no, customer_id, order_date, order_status, total_amount)
VALUES
    (1001, 'ORD-202601-001', 1, '2026-01-08', 'COMPLETED', 4495.00),
    (1002, 'ORD-202601-002', 2, '2026-01-12', 'COMPLETED', 6179.00),
    (1003, 'ORD-202601-003', 3, '2026-01-19', 'PAID', 3680.00),
    (1004, 'ORD-202601-004', 4, '2026-01-23', 'CANCELLED', 1980.00),
    (1005, 'ORD-202602-001', 5, '2026-02-03', 'COMPLETED', 8160.00),
    (1006, 'ORD-202602-002', 6, '2026-02-07', 'PAID', 4740.00),
    (1007, 'ORD-202602-003', 1, '2026-02-14', 'COMPLETED', 2499.00),
    (1008, 'ORD-202602-004', 2, '2026-02-21', 'COMPLETED', 7360.00),
    (1009, 'ORD-202603-001', 3, '2026-03-02', 'PAID', 3960.00),
    (1010, 'ORD-202603-002', 4, '2026-03-08', 'COMPLETED', 4979.00),
    (1011, 'ORD-202603-003', 5, '2026-03-15', 'REFUNDED', 1580.00),
    (1012, 'ORD-202603-004', 6, '2026-03-21', 'COMPLETED', 6179.00);

INSERT IGNORE INTO demo_order_item
    (id, order_id, product_id, quantity, unit_price)
VALUES
    (1, 1001, 1, 5, 899.00),
    (2, 1002, 2, 1, 2499.00),
    (3, 1002, 3, 1, 680.00),
    (4, 1002, 4, 1, 3000.00),
    (5, 1003, 4, 1, 3680.00),
    (6, 1004, 5, 1, 1980.00),
    (7, 1005, 1, 4, 899.00),
    (8, 1005, 6, 3, 1580.00),
    (9, 1006, 6, 3, 1580.00),
    (10, 1007, 2, 1, 2499.00),
    (11, 1008, 4, 2, 3680.00),
    (12, 1009, 3, 2, 680.00),
    (13, 1009, 5, 1, 2600.00),
    (14, 1010, 2, 1, 2499.00),
    (15, 1010, 1, 2, 899.00),
    (16, 1010, 3, 1, 680.00),
    (17, 1010, 4, 1, 301.00),
    (18, 1011, 6, 1, 1580.00),
    (19, 1012, 2, 1, 2499.00),
    (20, 1012, 6, 1, 1580.00),
    (21, 1012, 3, 3, 700.00);
