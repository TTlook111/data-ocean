-- LangGraph acceptance fixture only. Never run against a shared or production database.
CREATE TABLE IF NOT EXISTS products (
    product_id INT NOT NULL PRIMARY KEY,
    product_name VARCHAR(80) NOT NULL,
    category VARCHAR(40) NOT NULL
);

CREATE TABLE IF NOT EXISTS sales_orders (
    order_id INT NOT NULL PRIMARY KEY,
    order_date DATE NOT NULL,
    region VARCHAR(40) NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT fk_sales_orders_product FOREIGN KEY (product_id) REFERENCES products(product_id)
);

CREATE TABLE IF NOT EXISTS customers (
    customer_id INT NOT NULL PRIMARY KEY,
    customer_name VARCHAR(80) NOT NULL,
    phone VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS employee_pay (
    employee_id INT NOT NULL PRIMARY KEY,
    employee_name VARCHAR(80) NOT NULL,
    salary DECIMAL(12, 2) NOT NULL
);

DELETE FROM sales_orders;
DELETE FROM products;
DELETE FROM customers;
DELETE FROM employee_pay;

INSERT INTO products (product_id, product_name, category) VALUES
    (101, 'Office chair', 'Furniture'),
    (102, 'Desk', 'Furniture'),
    (103, 'Cable', 'Accessories');

INSERT INTO sales_orders (order_id, order_date, region, product_id, quantity, unit_price, status) VALUES
    (1, '2026-01-02', 'North', 101, 2, 10.00, 'COMPLETED'),
    (2, '2026-01-18', 'North', 102, 1, 30.00, 'COMPLETED'),
    (3, '2026-02-05', 'South', 102, 3, 30.00, 'COMPLETED'),
    (4, '2026-02-20', 'East', 103, 1, 15.00, 'RETURNED'),
    (5, '2026-03-10', 'East', 101, 4, 10.00, 'COMPLETED'),
    (6, '2025-12-15', 'South', 103, 2, 15.00, 'COMPLETED');

INSERT INTO customers (customer_id, customer_name, phone) VALUES
    (1, 'Fixture Customer A', '555-0101'),
    (2, 'Fixture Customer B', '555-0102');

INSERT INTO employee_pay (employee_id, employee_name, salary) VALUES
    (1, 'Fixture Employee A', 90000.00),
    (2, 'Fixture Employee B', 110000.00);

CREATE USER IF NOT EXISTS 'langgraph_fixture_reader'@'%' IDENTIFIED BY 'langgraph-fixture-reader-20260926';
ALTER USER 'langgraph_fixture_reader'@'%' IDENTIFIED BY 'langgraph-fixture-reader-20260926';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'langgraph_fixture_reader'@'%';
GRANT SELECT ON langgraph_fixture.products TO 'langgraph_fixture_reader'@'%';
GRANT SELECT ON langgraph_fixture.sales_orders TO 'langgraph_fixture_reader'@'%';
FLUSH PRIVILEGES;
