# LangGraph acceptance fixture knowledge

> Test-only reviewed document. Source: fixture business owner review, 2026-09-26.
> Bound snapshot: 8801. Review status: APPROVED. Do not use for any non-test datasource.

## Tables and fields

### `sales_orders` — order facts

Snapshot fields: `order_id` (INT), `order_date` (DATE), `region` (VARCHAR), `product_id` (INT), `quantity` (INT), `unit_price` (DECIMAL), `status` (VARCHAR).

- `order_date` is the business order date.
- `quantity` is the number of units; `unit_price` is the per-unit amount.
- `region` is the fixture sales region.
- All seven field meanings and ownership above are fixture facts; no personal customer fields are granted.

### `products` — product lookup

Snapshot fields: `product_id` (INT), `product_name` (VARCHAR), `category` (VARCHAR).

## Confirmed metric and filter

- Fixture gross revenue is `SUM(quantity * unit_price)`.
- Standard sales reporting includes only rows where `status = 'COMPLETED'`.
- Periods use half-open date ranges (`>= start` and `< next period`).

## Confirmed Join Path

- `sales_orders.product_id = products.product_id` is a database foreign key in snapshot 8801.
- This Join is approved for category and product reporting.

## Restricted resources

- `customers.customer_name` and `customers.phone` are not granted to the test user and must not enter model context or SQL.
- `employee_pay` is not in the authorized snapshot and must not enter model context or SQL.

## Unavailable business facts

- Advertising attribution is not present in the fixture schema; questions about campaign performance must be declined or clarified.
- Any other field meaning not listed here is “待确认”.
