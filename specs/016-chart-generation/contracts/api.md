# API Contracts: 图表生成与结果解释模块

## Base URL

- Python 内部: `/internal/chart`

## Authentication

内部接口通过服务间认证（X-Internal-Token header）。

---

## POST /internal/chart/generate

根据查询结果数据生成 ECharts Option 配置。

**Request**:
```json
{
  "question": "各部门上月销售额对比",
  "data_preview": [
    {"department": "华东区", "total_amount": 1520000},
    {"department": "华北区", "total_amount": 1380000},
    {"department": "华南区", "total_amount": 980000},
    {"department": "西南区", "total_amount": 650000}
  ],
  "column_types": {
    "department": "VARCHAR",
    "total_amount": "DECIMAL"
  },
  "total_rows": 4
}
```

**Response 200** (成功生成图表):
```json
{
  "chart_type": "bar",
  "echarts_option": {
    "title": { "text": "各部门上月销售额对比" },
    "tooltip": { "trigger": "axis" },
    "xAxis": {
      "type": "category",
      "data": ["华东区", "华北区", "华南区", "西南区"]
    },
    "yAxis": { "type": "value", "name": "销售额(元)" },
    "series": [
      {
        "name": "销售额",
        "type": "bar",
        "data": [1520000, 1380000, 980000, 650000]
      }
    ]
  },
  "suggested_types": ["bar", "pie", "line"],
  "aggregated": false
}
```

**Response 200** (数据不适合图表):
```json
{
  "chart_type": null,
  "echarts_option": null,
  "suggested_types": [],
  "aggregated": false,
  "reason": "单行单列数据不适合图表展示"
}
```

**Response 200** (LLM 生成失败，降级):
```json
{
  "chart_type": null,
  "echarts_option": null,
  "suggested_types": [],
  "aggregated": false,
  "reason": "图表配置生成失败，请查看表格数据"
}
```

---

## POST /internal/chart/aggregate

对大数据量进行聚合后再生成图表（内部调用，由 Data_Visualizer_Node 自动触发）。

**Request**:
```json
{
  "question": "过去一年每日订单量趋势",
  "data_preview": [
    {"date": "2025-06-01", "order_count": 156},
    {"date": "2025-06-02", "order_count": 142}
  ],
  "column_types": {
    "date": "DATE",
    "order_count": "INT"
  },
  "total_rows": 365
}
```

**Response 200**:
```json
{
  "chart_type": "line",
  "echarts_option": {
    "title": { "text": "过去一年订单量趋势（按月聚合）" },
    "tooltip": { "trigger": "axis" },
    "xAxis": {
      "type": "category",
      "data": ["2025-06", "2025-07", "2025-08", "2025-09", "2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03", "2026-04", "2026-05"]
    },
    "yAxis": { "type": "value", "name": "订单量" },
    "series": [
      {
        "name": "月订单量",
        "type": "line",
        "smooth": true,
        "data": [4520, 4680, 4890, 4350, 5120, 5560, 6200, 4800, 4200, 5100, 5300, 4900]
      }
    ]
  },
  "suggested_types": ["line", "bar"],
  "aggregated": true,
  "aggregation_note": "数据已按月聚合（原始 365 行 → 12 行）"
}
```

---

## Query Response with Chart (Java → Frontend)

Java 网关返回给前端的完整查询响应中包含图表数据：

```json
{
  "code": 200,
  "data": {
    "taskId": "task-abc123",
    "sql": "SELECT department, SUM(amount) as total_amount FROM orders GROUP BY department",
    "columns": ["department", "total_amount"],
    "rows": [
      ["华东区", 1520000],
      ["华北区", 1380000],
      ["华南区", 980000],
      ["西南区", 650000]
    ],
    "totalRows": 4,
    "chart": {
      "chartType": "bar",
      "echartsOption": { "...ECharts Option JSON..." },
      "suggestedTypes": ["bar", "pie", "line"],
      "aggregated": false
    },
    "explanation": {
      "tablesUsed": ["orders"],
      "columnsUsed": [
        { "table": "orders", "column": "department", "confidence": 90 },
        { "table": "orders", "column": "amount", "confidence": 95, "definition": "订单金额" }
      ],
      "lowConfidenceWarning": false,
      "dataScope": "全部数据"
    }
  }
}
```
