# 数据血缘开源项目调研与落地方案

> 调研日期：2026-07-28
> 调研范围：8 个代表性开源数据血缘项目
> 调研目标：为 DataOcean 血缘功能模块设计提供可借鉴的架构、数据模型和实现方案

---

## 一、调研项目概览

| 项目 | GitHub | Stars | 核心定位 | 推荐指数 | 一句话亮点 |
|------|--------|-------|---------|---------|-----------|
| **OpenMetadata** | [open-metadata/OpenMetadata](https://github.com/open-metadata/OpenMetadata) | ~14,400 | 统一元数据平台 | ⭐⭐⭐⭐⭐ | 列级血缘最成熟，手动拖拽编辑器 + 自动 SQL 解析，FQN 体系与 DataOcean 高度相似 |
| **DataHub** | [datahub-project/datahub](https://github.com/datahub-project/datahub) | ~12,300 | AI 数据目录平台 | ⭐⭐⭐⭐⭐ | sqlglot 驱动的列级血缘解析（97-99% 准确率），手动编辑+审计追踪 |
| **OpenLineage** | [OpenLineage/OpenLineage](https://github.com/OpenLineage/OpenLineage) | ~2,500 | 血缘开放标准 | ⭐⭐⭐⭐ | 事实上的行业标准，ColumnLineageDatasetFacet 规范是列级血缘的最佳参考 |
| **Marquez** | [MarquezProject/marquez](https://github.com/MarquezProject/marquez) | ~1,900 | 血缘专用平台 | ⭐⭐⭐⭐ | OpenLineage 参考实现，Job/Run/Dataset 模型设计优秀，列级血缘 API 设计值得借鉴 |
| **Spline** | [AbsaOSS/spline](https://github.com/AbsaOSS/spline) | ~650 | Spark 血缘追踪 | ⭐⭐⭐ | 属性图模型 + 表达式级血缘追踪，derivesFrom + computedBy 是列级派生的最佳实践 |
| **Egeria** | [odpi/egeria](https://github.com/odpi/egeria) | ~747 | 元数据治理平台 | ⭐⭐⭐ | LineageMapping + DataMapping 关系类型设计全面，治理驱动的血缘闭环思想可借鉴 |
| **Amundsen** | [amundsen-io/amundsen](https://github.com/amundsen-io/amundsen) | ~4,800 | 数据发现引擎 | ⭐⭐⭐ | Neo4j 属性图 + PageRank 搜索，但项目处于维护模式 |
| **Apache Atlas** | [apache/atlas](https://github.com/apache/atlas) | ~2,000 | Hadoop 元数据治理 | ⭐⭐ | DataSet→Process→DataSet 有向图模型经典，但架构过重、社区衰退 |

### 推荐结论

对于 DataOcean 的 NL2SQL 治理平台定位，**OpenMetadata** 和 **DataHub** 是最核心的参考对象——它们与 DataOcean 同属"元数据治理驱动"范式，在列级血缘建模、自动 SQL 解析、手动血缘编辑三个维度最为成熟。**OpenLineage** 是列级血缘数据格式的标准参考。**Spline** 的属性图模型（derivesFrom + computedBy）是表达列→列派生关系的最佳范例。

---

## 二、各项目深度分析

### 2.1 OpenMetadata ⭐⭐⭐⭐⭐ — 最核心参考

**项目简介**：OpenMetadata 是统一元数据管理平台，涵盖数据发现、可观测性、数据治理、血缘追踪。核心代码 TypeScript(UI) + Java(API) + Python(Ingestion)，已发布 v1.13.1（2026-06-27），社区极活跃。

**核心设计亮点**：

- **四级层级 FQN**：`service.database.schema.table.column`，与 DataOcean 的 FQN 体系（`datasource.db.table.column`）高度对齐
- **统一关系边表**：`entity_relationship` 表存储所有关系，relation type 包括 `CONTAINS`、`HAS`、`OWNS`、`UPSTREAM/DOWNSTREAM`——与 DataOcean 的 `metadata_relationship` 表设计思路一致
- **三阶段 SQL 解析链**：SqlGlot（30s 超时）→ SqlFluff → SqlParse，支持 24+ SQL 方言
- **列级血缘 AddLineageRequest**：每条边携带 `columnsLineage: [{fromColumns: ["fqn1","fqn2"], toColumn: "target_fqn"}]`
- **NetworkX 中间表分析**：有向图找弱连通分量，自动追溯通过 staging/temp 表的血缘路径（max depth 20）
- **手动拖拽编辑器**：在血缘图上直接添加/编辑/删除边和列映射
- **多图层血缘视图**：列图层、可观测性图层（质量测试结果）、服务图层、业务域图层

**数据模型分析**：

```text
实体类型：Table, Dashboard, Pipeline, Topic, ML Model, Container 等
关系类型：CONTAINS, HAS, OWNS, UPSTREAM, DOWNSTREAM
FQN 模式：service.database.schema.table.column（哈希分段存储）
列级血缘：ColumnLineage { fromColumns: [FQN], toColumn: FQN }
血缘来源枚举：QueryLineage, DashboardLineage, PipelineLineage, Manual
```

**血缘实现方案**：

| 采集方式 | 说明 |
|---------|------|
| 自动 SQL 解析 | 从查询日志批量提取（每批 200 条），SqlGlot→SqlFluff→SqlParse 级联解析 |
| View DDL 解析 | 采集时自动解析 CREATE VIEW 提取源表→视图血缘 |
| dbt Manifest | 解析 dbt 项目 JSON 获取模型级血缘 |
| 管道连接器 | Airflow/Fivetran/Dagster/Airbyte 自动提取管道→表血缘 |
| 手动 API | `PUT /api/v1/lineage` 接收 AddLineageRequest |
| 手动 UI 编辑器 | no-code 拖拽添加节点和边，展开表选择列映射 |

**可视化方案**：React Flow (`@xyflow/react`) 交互式图谱，支持展开/折叠节点（可配深度）、边详情查看（含完整 SQL）、多图层切换、拖拽编辑。

**可借鉴点**：

- ✅ **可直接借鉴**：
  - `ColumnLineage { fromColumns, toColumn }` 的数据结构——直接作为 DataOcean `DERIVED_FROM` 关系的 `relation_metadata.column_mappings` 格式
  - `AddLineageRequest` API 设计——作为 ETL/MANUAL 血缘创建的 API 参考
  - 三阶段 SQL 解析链思想——DataOcean 已有 sqlglot，可在此基础上增强列级提取
  - `LineageSource` 枚举（Query/Pipeline/Dashboard/Manual）——与 DataOcean 的 QUERY/ETL/MANUAL 对齐

- ⚠️ **需要改造**：
  - OpenMetadata 的 4 层架构（TypeScript+Java+Python+ES）太重，DataOcean 只需在现有的 metadata_relationship 表上扩展
  - NetworkX 中间表分析可用于 Python Agent 侧增强 SQL 血缘提取

- ❌ **暂不考虑**：
  - Elasticsearch 搜索——DataOcean 已有 MySQL FULLTEXT 索引
  - 管道/仪表板/ML 模型实体类型——超越 DataOcean 当前需求

---

### 2.2 DataHub ⭐⭐⭐⭐⭐ — 血缘解析与交互编辑标杆

**项目简介**：LinkedIn 开源、Linux Foundation 治理的 AI 数据目录平台。Java(GMS) + Python(Ingestion) + TypeScript/React(UI)，v1.6.0（2026），12K+ Stars，80+ 连接器，LinkedIn 级规模验证（10M+ 资产，O(1B) 关系）。

**核心设计亮点**：

- **schema-aware SQL 解析**：基于 sqlglot，解析时实时从 DataHub 后端拉取 schema 信息解析非限定表/列引用，准确率 97-99%
- **SchemaField 是一等实体**：字段有独立 URN（`urn:li:schemaField:(dataset_urn, field_path)`），可直接打标签、关联术语、设置权限
- **fineGrainedLineages**：列级血缘存储在 `upstreamLineage` aspect 中，每对源-目标列映射可携带 transformation 描述
- **手动编辑 + 审计追踪**：血缘可视图上直接增删上下游边，记录操作人和时间（avatar + timestamp 显示在边上）
- **Temp Table Stitching**：SQLite 驱动的会话级临时表解析，追溯到真实源表
- **Kafka 实时事件流**：元数据变更通过 Kafka 流式传播，近实时血缘更新

**数据模型分析**：

```text
核心抽象：Entity（节点）+ Aspect（独立版本化的原子写入单元）+ Relationship（命名双向边）

URN 模式（Dataset）：
  urn:li:dataset:(urn:li:dataPlatform:<platform>,<fqn>,<env>)

URN 模式（SchemaField）：
  urn:li:schemaField:(<parent_dataset_urn>,<encoded_field_path>)

表级血缘（upstreamLineage aspect）：
  upstreams: [{ dataset: URN, type: COPY/TRANSFORMED/VIEW }]

列级血缘（fineGrainedLineages）：
  { upstreamType: FIELD_SET, upstreamFields: [URN],
    downstreamType: FIELD, downstreamField: URN,
    transformation: "CAST(amount AS DECIMAL)" }

关系类型（@Relationship annotation）：
  OwnedBy, Contains, DerivedFrom (schemaField→schemaField)
```

**血缘实现方案**：

| 采集方式 | 说明 |
|---------|------|
| 查询日志自动提取 | BigQuery/Snowflake/Redshift/Databricks 原生查询历史 |
| schema-aware SQL 解析 | sqlglot 驱动，30+ 方言，处理 CTE/子查询/SELECT */UNION ALL |
| dbt/LookML/Tableau | 解析 manifest.json/LookML/ 计算字段表达式 |
| Airflow Plugin v2 | 在 OpenLineage 提取器上叠加 DataHub 的列级 SQL 解析 |
| Python SDK | `DataHubGraph.parse_sql_lineage()` + `add_lineage()` |
| **手动编辑 UI** | 血缘图节点菜单 → "Edit Upstream/Downstream" → 搜索选择 + 确认 |
| GraphQL API | `updateLineage` mutation 供外部系统编程式编辑 |

**可视化方案**：Airbnb visx（基于 D3 的低层 SVG 原语）+ React。支持节点展开/折叠、列级血缘展开、边 hover tooltip、手动编辑菜单（ManageLineageMenu.tsx）。核心文件集中在 `datahub-web-react/src/app/lineage/`。

**可借鉴点**：

- ✅ **可直接借鉴**：
  - `fineGrainedLineages` 的列级血缘数据结构——直接作为 `DERIVED_FROM` 关系的 metadata 格式
  - SchemaField 一等实体思想——DataOcean 的 COLUMN 实体已有独立 FQN，只需补 DERIVED_FROM 边
  - 手动编辑 UI 的交互模式——节点菜单 → 搜索/选择 → 确认，带审计追踪
  - sqlglot schema-aware 解析——与 DataOcean 已使用的 sqlglot 一致

- ⚠️ **需要改造**：
  - Aspect 独立版本化机制太重，DataOcean 只需在 relation_metadata JSON 字段记录版本
  - Kafka 事件流——DataOcean 目前无 Kafka，用 MySQL 事件表 + Spring Event 替代
  - URN 体系——DataOcean 已有 FQN，不需要引入 URN

- ❌ **暂不考虑**：
  - Neo4j 图存储——DataOcean 用 MySQL 的 metadata_relationship 表
  - 80+ 连接器——DataOcean 只需 MySQL 数据源

---

### 2.3 OpenLineage ⭐⭐⭐⭐ — 列级血缘数据格式标准

**项目简介**：Linux Foundation 项目，定义数据血缘元数据采集和交换的开放标准。Java 核心 + Python/Java 客户端。已发布 v1.51.0（2026-07-06），被 DataHub、Atlan、OpenMetadata、Egeria 等平台消费。

**核心设计亮点**：

- **Job/Run/Dataset 三实体模型**：Job（过程定义）→ Run（执行实例）→ Dataset（数据集），通过 Run 的 inputs/outputs 编织全局血缘图
- **ColumnLineageDatasetFacet**：列级血缘的规范定义，是行业最佳参考
- **Facet 扩展机制**：自描述的 JSON 元数据附件，支持自定义前缀命名空间避免冲突
- **不可变版本化**：Job 版本从 inputs/outputs/source 确定性计算，Dataset 版本不可变快照
- **多传输方式**：HTTP、Kafka、Console、File、Composite（多后端扇出）

**ColumnLineageDatasetFacet 规范**（最核心的参考）：

```json
{
  "columnLineage": {
    "_producer": "https://github.com/OpenLineage/OpenLineage",
    "_schemaURL": "https://openlineage.io/spec/facets/1-0-2/ColumnLineageDatasetFacet.json",
    "fields": {
      "target_column_1": {
        "inputFields": [
          {
            "namespace": "my_db",
            "name": "my_schema.my_source_table.source_column_a",
            "field": "source_column_a",
            "transformations": [
              {
                "type": "IDENTITY",
                "subtype": "DIRECT",
                "description": "Direct pass-through"
              }
            ]
          },
          {
            "namespace": "my_db",
            "name": "my_schema.my_source_table.source_column_b",
            "field": "source_column_b",
            "transformations": [
              {
                "type": "TRANSFORMATION",
                "subtype": "AGGREGATION",
                "description": "SUM(source_column_b) grouped by source_column_a"
              }
            ]
          }
        ]
      }
    }
  }
}
```

**Transformation 类型**（OpenLineage 规范）：
- Type 层：`IDENTITY`（直传，不改变值）、`TRANSFORMATION`（经过计算/转换）、`MASKED`（脱敏）
- Subtype 层：`DIRECT`/`INDIRECT`（直接/间接引用）、`AGGREGATION`（聚合）等
- DataOcean API 使用简化子集（`IDENTITY | TRANSFORMATION | AGGREGATION`），落库时映射到更细粒度的 `expression_type`（见 §4.2.2）。

**可借鉴点**：

- ✅ **可直接借鉴**：
  - `ColumnLineageDatasetFacet` 的字段映射格式——作为 DataOcean `DERIVED_FROM` 关系 `relation_metadata` 的 JSON schema
  - `TransformationType` 枚举——定义列级派生的转换类型（IDENTITY/TRANSFORMATION/AGGREGATION/MASKED）
  - 不可变版本化思想——DataOcean 的快照版本机制已部分实现

- ⚠️ **需要改造**：
  - Job/Run 概念——DataOcean 的 query_task 已是类似的 Run 概念
  - 事件驱动采集——DataOcean 当前是请求-响应模式

- ❌ **暂不考虑**：
  - 完整事件流基础设施——超出当前阶段需求

---

### 2.4 Marquez ⭐⭐⭐⭐ — 列级血缘 API 设计参考

**项目简介**：OpenLineage 的参考实现，纯血缘专用平台。Java + PostgreSQL + React/D3.js，LF AI & Data 毕业项目（2023-09）。

**核心设计亮点**：

- **Job→Run→Dataset 三实体**：与 OpenLineage 一致，Run 关联 inputs/outputs 形成血缘 DAG
- **列级血缘独立 API**：`GET /api/v1/column-lineage/{nodeId}` 的深度遍历设计
- **不可变版本历史**：dataset_versions 和 job_versions 表追踪每次变更
- **GraphQL 端点**（beta）：灵活查询血缘数据
- **FQN 模式**：`{namespace}.{schema}.{table}`，与 DataOcean 相似

**列级血缘 API**（可直接参考的设计）：

```text
GET /api/v1/column-lineage/{nodeId}?depth=5&withDownstream=true
  → { graph: [...], nodeId: "datasetField:ns:public.orders:total_amount" }

节点 ID 格式：datasetField:{namespace}:{dataset}:{field}
入边/出边：每个字段节点携带 inEdges/outEdges 列表
```

**可借鉴点**：

- ✅ **可直接借鉴**：
  - `GET /api/v1/column-lineage/{nodeId}?depth=N` 的列级血缘查询 API 设计——DataOcean 可新增 `/api/admin/catalog/entities/{id}/column-lineage`
  - 节点 ID 格式 `{entityType}:{namespace}:{name}` ——简洁直观
  - 不可变版本化的实现方案

- ❌ **暂不考虑**：
  - Marquez 本身无治理能力（无审批流、质量检查、置信度评分）

---

### 2.5 Spline ⭐⭐⭐ — 属性图 + 表达式级血缘

**项目简介**：Absa 开源的 Spark 数据血缘追踪平台。Scala + ArangoDB + Angular。**表达式级血缘**是其最大亮点——追踪到每个操作符级别。

**核心设计亮点**：

- **属性图模型**：节点=ExecutionPlan/Operation/DataSource/Schema/Attribute/Expression，边=produces/derivesFrom/computedBy
- **表达式级血缘**：`derivesFrom`（Attribute→Attribute，列级派生）+ `computedBy`（Attribute→Expression，记录了哪个表达式计算出该列）+ `takes`（Expression→Expression/Attribute，带操作数索引）
- **跨作业血缘**：通过 DataSource URI 匹配连接不同 ExecutionPlan 的 attributes
- **零代码侵入**：Hooks Spark QueryExecutionListener，分析逻辑/物理执行计划

**属性图血缘边设计**（最值得参考的部分）：

```text
节点类型：
  Attribute { name, dataType }
  Expression { text: "SUM(amount * price)", dataType }

边类型：
  produces     Operation → Attribute        （哪个操作产出了这个属性）
  derivesFrom  Attribute → Attribute        （列→列的派生，列级血缘核心）
  computedBy   Attribute → Expression       （属性由哪个表达式计算）
  takes        Expression → Attribute       （表达式引用了哪个属性，带 operandIndex 顺序）
  takes        Expression → Expression      （嵌套表达式）
```

**可借鉴点**：

- ✅ **可直接借鉴**：
  - `derivesFrom + computedBy` 模式——作为 DataOcean `DERIVED_FROM` 关系的双通道设计
  - `derivesFrom`：连接"目标列→源列"，追踪来源
  - `computedBy`：连接"目标列→表达式"，记录转换逻辑
  - `relation_metadata` 中包含 `expression` 字段追踪"怎么算出来的"
  - DataSource URI 匹配的跨查询血缘连接模式

- ❌ **暂不考虑**：
  - ArangoDB 图数据库——DataOcean 用 MySQL
  - 仅支持 Spark 的限制

---

### 2.6 Egeria ⭐⭐⭐ — 治理驱动的血缘闭环

**项目简介**：ODPi/LF AI & Data 的开放元数据治理平台。Java 全栈，Apache 2.0。

**核心设计亮点**：

- **LineageMapping（Model 0770）**：跨工具"拼接"断开血缘图的关系类型，连接不同工具中代表同一对象的不同表示
- **DataMapping（Model 0770）**：列级关系，映射 schema attributes 之间的转换
- **三阶段血缘生命周期**：Capture（自动采集）→ Stewardship（人工治理，缝合断开图）→ Preservation（图数据库归档）
- **Memento 标记**：已删除资产保留血缘查询能力，不丢失追溯链
- **垂直+水平双维度血缘**：垂直=业务到技术，水平=源到消费

**可借鉴点**：

- ✅ **可直接借鉴**：
  - 三阶段管理思想——DataOcean 目前已实现 Capture（QUERY 自动血缘），需要补 Stewardship（ETL/MANUAL 手动创建+治理审批）
  - Memento 标记——当治理状态变为 DEPRECATED 时，对应的 LINEAGE 关系不应被删除，只标记状态
  - LineageMapping 概念——当同一个物理表在不同快照中有不同 ID 时，需要"拼接"关系

- ❌ **暂不考虑**：
  - 完整 OMAG 平台架构（太重）
  - Kafka 事件总线和 Metadata Highway 同行网络

---

### 2.7 Amundsen ⭐⭐⭐ — 图数据库血缘参考

**项目简介**：Lyft 开源的数据发现引擎（"Google search for data"），Python 全栈 + Neo4j + Elasticsearch。项目处于维护模式（2023 年起无新功能）。

**核心设计亮点**：

- **Neo4j 属性图**：节点=Table/Column/Dashboard/User，边=DEPENDS_ON/GENERATES/BELONGS_TO
- **双向边存储**：每条关系存一对（`source-[DEPENDS_ON]->target` 和 `target-[REVERSE_DEPENDS_ON]->source`），上下游查询都高效
- **Gateway 模式**：Metadata Service 作为代理调用外部血缘后端，聚合多源血缘数据
- **列级血缘 UI**：ColumnDetailsPanel 显示最多 5 层上游和 5 层下游
- **PageRank 风格搜索**：按使用频率排序

**可借鉴点**：

- ✅ **可直接借鉴**：
  - 双向边存储提升查询性能——DataOcean 当前只存单向 LINEAGE，下游查询需 BFS 遍历；可同时存入双向边降低查询复杂度
  - Gateway 聚合模式——如果未来引入外部血缘源，可作为聚合层设计

---

### 2.8 Apache Atlas ⭐⭐ — Hadoop 时代经典

**项目简介**：Apache 的集中式元数据治理平台，Java + JanusGraph + HBase + Solr + Kafka。

**知名但已衰退**：Star 数 ~2K 但社区活跃度下降。列级血缘仅限 Hive（不稳定），Spark 需要自定义连接器。以下只摘取其仍可借鉴的设计：

**可借鉴点**：

- ✅ **可直接借鉴**：
  - `DataSet → Process → Dataset` 有向图模型——简洁直观的表级血缘表达
  - 类型系统的继承和组合——Entity 有超类型 Referenceable，子类型通过继承复用 qualifiedName 等属性

- ❌ **暂不考虑**：
  - 整套技术栈（HBase + Solr + JanusGraph）对 DataOcean 过重
  - 仅适用于传统 Hadoop 生态

---

## 三、横向对比

### 3.1 各维度评分对比（1-5 分，5 最优）

| 维度 | OpenMetadata | DataHub | OpenLineage | Marquez | Spline | Egeria | Amundsen | Atlas |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **数据模型设计** | 5 | 5 | 4 | 4 | 5 | 4 | 3 | 3 |
| **列级血缘能力** | 5 | 5 | 4 | 3 | 5 | 3 | 3 | 2 |
| **ETL/手动血缘** | 5 | 4 | 2 | 2 | 1 | 3 | 3 | 2 |
| **可视化交互** | 5 | 5 | 2 | 3 | 3 | 2 | 3 | 3 |
| **SQL 解析** | 4 | 5 | 3 | 1 | 1 | 2 | 1 | 3 |
| **与 DataOcean 契合度** | 5 | 4 | 3 | 3 | 2 | 3 | 2 | 1 |

### 3.2 关键维度横向分析

**数据模型设计**：

| 项目 | FQN 策略 | 关系存储 | 列级血缘表示 |
|------|---------|---------|------------|
| OpenMetadata | service.db.schema.table.column（哈希分段存储） | entity_relationship 表 relation type 字段 | ColumnLineage { fromColumns[], toColumn } |
| DataHub | urn:li:dataset:(platform,fqn) | @Relationship 注解 + Elasticsearch 图索引 | fineGrainedLineages: [{ upstreamFields, downstreamField }] |
| OpenLineage | namespace.name (dataset) | 无内置存储，通过 Facet 附加 | ColumnLineageDatasetFacet { fields: { target: { inputFields[] } } } |
| Spline | DataSource URI | ArangoDB 属性图边 | derivesFrom (Attribute→Attribute) + computedBy (Attribute→Expression) |
| **DataOcean 现有** | datasource.db.table.column | metadata_relationship 表 | ❌ 列级 LINEAGE 为空，DERIVED_FROM 未使用 |

**列级血缘方案对比**：

| 项目 | 自动提取方式 | 手动录入方式 | 表达式追溯 |
|------|------------|------------|----------|
| OpenMetadata | SqlGlot→SqlFluff→SqlParse 级联解析 | 拖拽编辑器选列 + 边详情填转换描述 | SQL 原文存储在 sqlQuery 字段 |
| DataHub | sqlglot schema-aware 解析（准确率 97-99%） | 节点菜单 → 搜索添加 | fineGrainedLineages.transformation 字段 |
| Spline | Spark LogicalPlan 自动分析 | ❌ 无手动录入 | computedBy 边 + Expression 节点 AST 树 |
| OpenLineage | Airflow SQLParser + SparkListener 等 | API POST | transformations 数组每项含 type/subtype/description |
| Marquez | 无内置 SQL 解析，依赖 OpenLineage 集成 | ❌ 无第一梯队支持 | ❌ 无表达式追溯 |

**手动血缘编辑对比**：

| 项目 | 是否支持 | 交互方式 | 审计追踪 |
|------|---------|---------|---------|
| OpenMetadata | ✅ | 拖拽添加节点→展开表→选列连线 | ❌ 无 |
| DataHub | ✅ | 节点菜单→搜索选择→确认（v0.9.5+） | ✅ avatar+timestamp 显示在边上 |
| Apache Atlas | ❌ 只读 | — | — |
| Marquez | ❌ 只读（issue #2624 讨论中） | — | — |

---

## 四、对当前项目的借鉴与落地建议

### 项目现状回顾

DataOcean 已有基础设施：
- `metadata_entity` 表：6 种实体类型（DATASOURCE/DATABASE/TABLE/COLUMN/GLOSSARY_TERM/TAG），FQN 体系 `datasource.db.table.column`
- `metadata_relationship` 表：8 种关系类型（CONTAINS/HAS_PART/LINEAGE/TAGGED_WITH/GLOSSARY_OF/FOREIGN_KEY/DERIVED_FROM/RELATED_TO）
- 3 种血缘子类型常量：`LINEAGE_QUERY`（自动）、`LINEAGE_ETL`（定义但无 API）、`LINEAGE_MANUAL`（定义但无 API）
- 自动血缘：快照发布→实体+CONTAINS+HAS_PART+FOREIGN_KEY；查询执行→LINEAGE(QUERY)
- 查询 API：BFS `getLineage()` / `getDownstream(maxDepth=10)`
- 前端：只读 ECharts 力导向 DAG（LineageGraph.vue）+ 表/列搜索+影响分析（LineageViewer.vue）

---

### 4.1 P0 — ETL/MANUAL 血缘创建能力 🎯 最高优先级

**借鉴来源**：OpenMetadata 的 `AddLineageRequest` API + DataHub 的 `updateLineage` mutation + OpenLineage 的 ColumnLineageDatasetFacet 格式

#### 4.1.1 后端 API 设计

**新增 API — 创建 LINEAGE 关系**：

```text
POST /api/admin/catalog/lineage
  Permission: metadata:manage 或 *

请求体：
{
  "sourceEntityId": 100,           // 源实体 ID（仅限 TABLE 类型；列级派生请用 DERIVED_FROM）
  "targetEntityId": 200,           // 目标实体 ID（仅限 TABLE 类型）
  "lineageType": "ETL",            // ETL | MANUAL（QUERY 由系统自动生成）
  "description": "daily_stats 由 orders 聚合产出",
  "columnMappings": [              // 列级映射（可选，填写后自动创建 DERIVED_FROM 边）
    {
      "fromColumns": [301, 302],   // 源列实体 ID 列表（COLUMN 类型，如 orders.amount, orders.price）
      "toColumn": 401,             // 目标列实体 ID（COLUMN 类型，如 daily_stats.total_amount）
      "expression": "SUM(amount * unit_price)",  // 转换表达式
      "transformationType": "AGGREGATION"        // IDENTITY | TRANSFORMATION | AGGREGATION
    }
  ]
}

响应：
{
  "code": 200,
  "data": {
    "relationshipId": 789,
    "sourceEntityId": 100,
    "targetEntityId": 200,
    "relationType": "LINEAGE",
    "relationMetadata": { ... }
  }
}
```

> **LINEAGE 与 DERIVED_FROM 的关系模型约定**：
>
> | 关系类型 | 源→目标 | 用途 | 创建时机 |
> |---------|---------|------|---------|
> | `LINEAGE` | TABLE → TABLE | 表级数据流转 | API 手动创建 / 查询执行自动提取 |
> | `DERIVED_FROM` | COLUMN → COLUMN | 列的计算依赖 | LINEAGE 创建时自动生成 / sqlglot 自动提取 |
>
> - LINEAGE 的 `columnMappings` 字段是所属 DERIVED_FROM 边的**附属摘要信息**，用于图谱展示时在表级边上显示列映射详情，不替代底层 DERIVED_FROM 边。
> - API 层必须校验 `sourceEntityId`/`targetEntityId` 的 `entity_type` 均为 `TABLE`，拒绝直接创建 COLUMN → COLUMN 的 LINEAGE。
> - 列级关系仅有 DERIVED_FROM 一种表达，避免同一事实被 LINEAGE 和 DERIVED_FROM 重复表达导致查询/展示不一致

**新增 API — 删除 LINEAGE 关系**：

```text
DELETE /api/admin/catalog/lineage/{relationshipId}
  Permission: metadata:manage 或 *

响应：200 成功 / 404 不存在
```

**新增 API — 批量创建 ETL 血缘**（用于 CSV/JSON 导入）：

```text
POST /api/admin/catalog/lineage/batch
  Content-Type: multipart/form-data
  body: file (.csv 或 .json)
```

**扩展已有 API — 增强 getLineage 返回**：

```text
GET /api/admin/catalog/entities/{id}/lineage?depth=3&lineageType=ETL,MANUAL,QUERY
  → 返回增加 columnMappings 字段，支持按 lineageType 过滤
```

#### 4.1.2 后端 Service 变更

**新增 `LineageEdgeService`**：

```java
// 创建 LINEAGE 关系
LineageEdgeVO createLineage(LineageCreateRequest request);

// 删除 LINEAGE 关系
void deleteLineage(Long relationshipId);

// 批量创建
List<LineageEdgeVO> batchCreateLineage(List<LineageCreateRequest> requests);

// 查询带列映射的完整血缘（当前 getLineage 只返回关系，扩展返回列映射）
LineageGraphVO getEnrichedLineage(Long entityId, int depth, Set<String> lineageTypes);
```

**扩展 `LineageServiceImpl.bridgeToEntityGraph()`**：当前只创建表间的 LINEAGE 边。需要增加列级 DERIVED_FROM 边的创建——对于 sqlglot 解析出的 column mappings，自动创建 COLUMN→COLUMN 的 DERIVED_FROM 关系。

#### 4.1.3 前端录入方案

**页面一：血缘手动录入表单**

在 `LineageGraph.vue` 或新增 `LineageEditor.vue` 中添加"添加血缘"按钮，弹出表单：

```
┌─────────────────────────────────────────────────┐
│  添加血缘关系                           [✕]      │
├─────────────────────────────────────────────────┤
│                                                   │
│  血缘类型：  ○ ETL 流转    ○ 手动标注            │
│                                                   │
│  源实体：    [🔍 搜索表...]      orders         │
│  目标实体：  [🔍 搜索表...]      daily_stats    │
│                                                   │
│  描述：      [daily_stats 由 orders 聚合产出  ]  │
│                                                   │
│  ▼ 列级映射（可选）                              │
│  ┌───────────────────────────────────────────┐   │
│  │ 源列           →  目标列        转换表达式  │   │
│  │ [orders.amount]   [total_amount] SUM(...)  │   │
│  │ [orders.price]    —                             │   │
│  │ [+ 添加列映射]                               │   │
│  └───────────────────────────────────────────┘   │
│                                                   │
│  [取消]                              [确认添加]   │
└─────────────────────────────────────────────────┘
```

- 源/目标实体搜索复用现有的 `searchCatalog` API（全文搜索 + `entityType=TABLE` 过滤，确保只能选择表）
- 列映射中的"源列"支持多选（参考 OpenLineage 的 `inputFields` 数组），"目标列"单选
- 表达式字段记录转换逻辑

**页面二：血缘图谱交互增强**（LineageGraph.vue 改造）

- 右键节点 → 菜单"添加下游血缘"→ 弹出上述表单，源实体预填当前选中实体
- 右键边 → 菜单"编辑"/"删除"
- 边 hover → tooltip 显示 lineageType、描述、操作人、时间

#### 4.1.4 数据库变更（如需要）

当前 `metadata_relationship` 表已有 `relation_metadata` JSON 字段，**核心血缘存储无需改表结构**。审计字段（`created_by`）建议后续通过 DDL 新增表列（见下方审计字段存储约定）。只需定义标准化的 JSON Schema：

```json
{
  "lineage_type": "ETL",
  "description": "daily_stats 由 orders 聚合产出",
  "column_mappings": [
    {
      "from_column_ids": [301, 302],
      "to_column_id": 401,
      "from_fqns": ["mysql_prod.mydb.orders.amount", "mysql_prod.mydb.orders.price"],
      "to_fqn": "mysql_prod.mydb.daily_stats.total_amount",
      "expression": "SUM(amount * unit_price)",
      "transformation_type": "AGGREGATION"
    }
  ]
}
```

> **存储口径说明**：
> - **主键存储**：`from_column_ids` / `to_column_id` 以实体 ID 为准。ID 不受列重命名影响，查询时通过 ID JOIN `metadata_entity` 获取当前 FQN。
> - **冗余加速**：`from_fqns` / `to_fqn` 在写入时同步填充，用于前端展示和全文搜索，避免每次渲染血缘图都做 N+1 JOIN。
> - **一致性维护**：快照重新发布导致列 FQN 变化时，通过 `SnapshotEntitySyncListener` 同步刷新相关 `relation_metadata` 中的 FQN 冗余字段。
>
> **审计字段存储约定**：
> - **表列优先**：`created_at` 使用 `metadata_relationship.created_at` 表列（已有），来源可靠且不可篡改。
> - **建议新增表列**：为 `metadata_relationship` 新增 `created_by VARCHAR(64)` 列（通过 MyBatis-Plus 自动填充从当前登录用户获取），审计信息的权威来源应放在表列而非 JSON。
> - **过渡期处理**：若暂未新增表列，可在 `relation_metadata` 中临时存放 `created_by` / `updated_at`。一旦表列补齐，JSON 中的审计字段标记为 `@Deprecated`，读取时优先使用表列。
> - **展示取数规则**：统一优先读取表标准审计列；表列缺失时才降级读取 `relation_metadata` 中的对应字段。

---

### 4.2 P1 — DERIVED_FROM 列级派生血缘 🎯 高优先级

**借鉴来源**：Spline 的 `derivesFrom + computedBy` 双通道 + OpenLineage 的 `ColumnLineageDatasetFacet.transformationTypes`

#### 4.2.1 让 DERIVED_FROM 关系活起来

当前 `DERIVED_FROM` 常量已定义但零使用。定义为：

> **COLUMN A `DERIVED_FROM` COLUMN B** = "列 A 的值是由列 B（或列 B+C...）经过计算得出的"

与 LINEAGE 的区别：
- `LINEAGE`（TABLE → TABLE）：数据在表之间流转。**不表达列级关系**，API 层校验两端实体类型必须为 TABLE。
- `DERIVED_FROM`（COLUMN → COLUMN）：列的计算依赖关系。**是列级关系的唯一表达方式**。
- 关联方式：一条 LINEAGE 边的 `relation_metadata.column_mappings` 携带所属 DERIVED_FROM 的摘要信息，用于图谱展示时在表级边上显示列映射详情。

#### 4.2.2 DERIVED_FROM 关系的数据结构

```json
// metadata_relationship 中 relation_metadata 的 DERIVED_FROM JSON Schema
{
  "expression": "amount * unit_price",          // 转换表达式（原始 SQL 片段）
  "expression_type": "ARITHMETIC",              // 表达式类型（DataOcean 规范，见下方枚举）
  "description": "单价 × 数量计算订单金额",
  "source": "SQL_PARSER",                       // 来源：SQL_PARSER | MANUAL | INFERRED
  "transformation": {                           // 可选：对齐 OpenLineage ColumnLineageDatasetFacet
    "type": "TRANSFORMATION",                   //   OpenLineage type：IDENTITY | TRANSFORMATION | MASKED
    "subtype": "ARITHMETIC",                    //   OpenLineage subtype：与 expression_type 对齐
    "description": "amount * unit_price"
  }
}
```

> **expression_type 枚举（DataOcean 规范，比 OpenLineage 更细粒度）**：
>
> | expression_type | 说明 | 对应 OpenLineage type/subtype | API transformationType |
> |----------------|------|------------------------------|----------------------|
> | `DIRECT` | 直接映射（列直传，无计算） | IDENTITY / DIRECT | IDENTITY |
> | `ARITHMETIC` | 算术运算（+ - * /） | TRANSFORMATION | TRANSFORMATION |
> | `AGGREGATION` | 聚合函数（SUM/COUNT/AVG/MAX/MIN） | TRANSFORMATION / AGGREGATION | AGGREGATION |
> | `CONCAT` | 字符串拼接 | TRANSFORMATION | TRANSFORMATION |
> | `CASE_WHEN` | 条件表达式 | TRANSFORMATION | TRANSFORMATION |
> | `CAST` | 类型转换 | TRANSFORMATION | TRANSFORMATION |
>
> - `expression_type` 是 DataOcean 的主存储字段，前端录入时通过 `transformationType`（简化版 3 选 1）自动映射。
> - `transformation` 子对象为可选字段，仅用于对外输出对齐 OpenLineage 标准，内部查询不依赖它。
> - 自动提取（sqlglot）时，直接写入 `expression_type`；手动录入时，用户选 `transformationType`，后端映射到 `expression_type`。

#### 4.2.3 自动提取方案

**增强 sqlglot 列级提取**：

当前 `LineageServiceImpl.saveLineage()` 只存了 used_columns（谁被引用了），没有提取列→列的派生关系。需要：

1. **Python 侧增强**：在 `sql_executor.py` 的 `_extract_columns()` 基础上，新增 `_extract_column_derivations()` 函数，解析 SQL AST 时同时提取列级派生关系
   - SELECT 子句：`SELECT a.amount * a.price AS total` → `total DERIVED_FROM [a.amount, a.price], expression="amount*price"`
   - 聚合：`SELECT SUM(b.sales) AS total_sales` → `total_sales DERIVED_FROM [b.sales], expression_type=AGGREGATION`
   - CASE WHEN：`SELECT CASE WHEN a > 0 THEN 'pos' ELSE 'neg' END` → 派生列 DERIVED_FROM [a]
   - **数据传递**：提取结果放入 `QueryResult.column_derivations` 新字段（结构化列表），随 SSE `result` 事件返回 Java 侧。

2. **Java 侧消费**：在 `LineageServiceImpl.bridgeToEntityGraph()` 中，除创建 LINEAGE 边外，消费 `QueryResult.column_derivations` 为每对派生列自动创建 DERIVED_FROM 关系。数据流：
   ```text
   Python sql_executor.py（sqlglot AST 提取列派生）
     → QueryResult.column_derivations (SSE)
       → Java QueryTaskServiceImpl（解析 SSE result）
         → LineageServiceImpl.bridgeToEntityGraph()（写入 DERIVED_FROM 边）
   ```

3. **参考 Spline 模式**：
   - 目标列 → `derivesFrom` → 源列（COLUMN→COLUMN 的 DERIVED_FROM 边）
   - 目标列 → `computedBy` → 表达式字符串（通过 relation_metadata.expression 字段存储）

#### 4.2.4 手动录入列级派生

在 P0 的列映射表单中填写列映射后，后端处理逻辑：
1. 创建一条 LINEAGE 边（TABLE → TABLE），`relation_metadata.column_mappings` 存储列映射摘要。
2. 为每条 `columnMappings` 条目自动创建一条 DERIVED_FROM 边（COLUMN → COLUMN），`relation_metadata` 存储表达式和转换类型。
3. **不会**创建 COLUMN → COLUMN 的 LINEAGE 边——列级关系仅有 DERIVED_FROM 一种表达，避免同一事实被两种关系类型重复存储。
4. 查询列级血缘时统一走 `GET /api/admin/catalog/entities/{columnId}/column-lineage`（只查 DERIVED_FROM 边），不存在去重歧义。

#### 4.2.5 列级血缘查询 API

```text
GET /api/admin/catalog/entities/{columnId}/column-lineage?depth=3&direction=both
  → 返回该列的上游（来源）和下游（影响）的 DERIVED_FROM 链
```

---

### 4.3 P2 — 血缘图谱交互编辑 🎯 中优先级

**借鉴来源**：OpenMetadata 的拖拽编辑器 + DataHub 的右键菜单编辑

#### 4.3.1 技术选型建议

DataOcean 已使用 **ECharts**（`LineageGraph.vue` 中），ECharts graph type 支持力导向布局，但**原生不支持交互式添加/删除节点和边**。有两个升级路径：

| 方案 | 库 | 优点 | 缺点 | 建议 |
|------|-----|------|------|------|
| A. 保持 ECharts | ECharts + 自定义事件 | 不需要引入新依赖 | 交互编辑需大量自定义代码 | 如果只做简单编辑 |
| B. 升级到 D3.js | D3.js v7 + dagre-d3 | 完全控制编辑交互，被 Marquez/Atlas 项目验证 | 需要重写 LineageGraph.vue，需新增依赖 | **推荐**，交互能力更强 |
| C. Vue Flow | @xyflow/vue | 原生支持拖拽节点、画连线、编辑属性，Vue 3 一等支持 | 需新增依赖，自定义节点/边有学习成本 | 可选方案，适合快速实现编辑交互 |
| D. vis-network | vis-network | 轻量、原生支持编辑 | 社区更新慢 | 可考虑作为折中方案 |

**推荐方案 B（D3.js + dagre-d3），备选方案 C（Vue Flow）**：
- D3.js 提供完全自定义的交互编辑能力，dagre 提供层次化布局（比力导向更适合血缘 DAG）
- Marquez UI 的 D3.js 血缘图验证了该方案在血缘场景的可行性
- 需新增 `d3` 和 `dagre-d3`（或 `@dagrejs/dagre`）依赖，体积可控（D3 v7 tree-shaking 后按需引入约 30-50KB gzip）
- Vue 3 中可直接操作 SVG DOM，无需 React 桥接层

> **方案 B vs C 选择建议**：
> - 如果团队对 D3 熟悉或需要完全自定义的交互（拖拽连线、列节点展开/折叠、表达式节点等），选 B。
> - 如果希望更快出 MVP 且交互复杂度适中，选 C（Vue Flow），它自带节点拖拽、连线、小地图等开箱即用功能。
> - 当前推荐 B 主要是考虑到后续需要深度定制血缘图交互（列图层切换、表达式节点、多图层叠加），D3 的灵活性更适配长期需求。React-only 方案（如 @xyflow/react）不在考虑范围内。

#### 4.3.2 交互设计

**右键菜单**：
```
节点（表）右键：
  ┌──────────────────┐
  │ 📋 查看详情       │
  │ 🔍 展开上游       │
  │ 🔍 展开下游       │
  │ ─────────────── │
  │ ➕ 添加下游血缘   │  → 弹出 P0 的表单，源实体预填当前节点
  │ ✏️ 编辑属性       │  → 行内编辑
  │ 🗑️ 从图谱移除     │  → 仅移除显示，不删除数据
  └──────────────────┘

边（连线）右键：
  ┌──────────────────┐
  │ 📋 查看详情       │  → tooltip 显示：类型/描述/创建人/时间
  │ ✏️ 编辑           │  → 弹出编辑表单
  │ 🗑️ 删除此血缘     │  → 确认后调用 DELETE API
  └──────────────────┘

空白区域右键：
  ┌──────────────────┐
  │ ➕ 添加血缘关系   │  → 弹出 P0 表单
  │ 🔄 刷新图谱       │
  │ 📷 导出 PNG       │
  └──────────────────┘
```

**拖拽交互**：
- 从表节点拖出连线，释放到另一个表节点→自动创建 LINEAGE 边（TABLE → TABLE）
- 从表节点的"展开列"区域拖出列→释放到目标表的列→自动创建 DERIVED_FROM 边（COLUMN → COLUMN）；若两端表之间尚不存在 LINEAGE 边，同时自动创建一条 LINEAGE 边（TABLE → TABLE），确保 DERIVED_FROM 不会成为孤立边
- 拖拽创建的关系默认 `lineageType=MANUAL`，可在边上右键编辑修改

**审计信息显示**：每条新增/手动编辑的边显示操作人和时间（参考 DataHub 的 avatar + timestamp）

#### 4.3.3 大图处理

随着血缘数据积累，图可能包含数百个节点。需要：
- **默认折叠列节点**：初始只显示 TABLE 节点，点击展开才能看到 COLUMN
- **深度限制**：默认显示 2 层上下游，提供深度滑块（1-10）
- **过滤**：按 lineageType（QUERY/ETL/MANUAL）、按数据源
- **搜索高亮**：搜索某表名，高亮并居中

---

### 4.4 P3 — 血缘数据接入 RAG 🎯 远期规划

**借鉴来源**：DataHub 的搜索驱动的元数据问答 + OpenMetadata 的可观测性图层

#### 4.4.1 血缘数据如何增强 Schema Linking

当前 Schema Linking 只知道表名和列名，不知道关系。接入血缘后：

1. **FOREIGN_KEY 关系 → JOIN 推荐**：
   - 当用户问题涉及两张表，且 metadata_relationship 中存在 FOREIGN_KEY 关系时，自动向 LLM 提供 JOIN 条件
   - 格式示例：`Table orders JOIN table customers ON orders.customer_id = customers.id [FOREIGN_KEY]`

2. **LINEAGE 关系 → 表推荐**：
   - 当用户问"订单数据从哪里来"，RAG 能通过 LINEAGE(ETL) 边找到上游表

3. **DERIVED_FROM 关系 → 字段解释**：
   - 当用户问"total_amount 是怎么算的"，RAG 能通过 DERIVED_FROM 边找到源字段和表达式

#### 4.4.2 实施方案

```text
Java 侧新增内部 API（供 Python 调用）:

GET /internal/metadata/relationships?entityId=123&relationType=FOREIGN_KEY,LINEAGE,DERIVED_FROM
  → 返回该实体相关的所有关系，供 Python Agent 在 Schema Linking 阶段消费
  
Python Agent 修改:

1. schema_retriever.py: 在检索列信息时，同时查询该表/列的关系数据
2. state.py: RetrievedSchema 增加 relationships 字段
3. schema_linking.py: 提示词中增加"可用的表间关系"上下文
```

#### 4.4.3 优先级说明

P3 是远期规划，应在 P0-P2 完成后、血缘数据积累到一定量（至少每个数据源有 50+ 条 LINEAGE 边和 100+ 条 DERIVED_FROM 边）时再接入 RAG，否则上下文噪声大于信息增益。

---

## 五、实施路线图

```
Phase 0 (P0) — ETL/MANUAL 血缘创建    [预计 3-5 天]
  ├── 后端：新增 LineageEdgeService + Controller
  ├── 前端：血缘录入表单 + 图谱右键菜单
  └── 验收：能手动创建 ETL/MANUAL 血缘边，在图谱上可见

Phase 1 (P1) — DERIVED_FROM 列级派生   [预计 3-5 天]
  ├── 后端：扩展 bridgeToEntityGraph 创建 DERIVED_FROM 边
  ├── Python：增强 sqlglot 列级派生提取
  ├── 前端：列映射录入 UI（在 P0 表单基础上扩展）
  └── 验收：查询执行后自动生成 DERIVED_FROM 边；列映射表单可用

Phase 2 (P2) — 图谱交互编辑升级        [预计 5-7 天]
  ├── 前端：D3.js + dagre 重写 LineageGraph.vue
  ├── 支持拖拽连线、右键编辑/删除、审计显示
  └── 验收：图谱上可直接增删改血缘边

Phase 3 (P3) — RAG 接入               [预计 2-3 天，依赖血缘数据积累]
  ├── Java：新增 /internal/metadata/relationships 内部 API
  ├── Python：Schema Linking 消费关系数据
  └── 验收：RAG 能推荐 JOIN 条件、表关联
```

---

## 六、参考项目清单

| 项目 | GitHub | 文档/代码参考 |
|------|--------|------------|
| OpenMetadata | https://github.com/open-metadata/OpenMetadata | AddLineageRequest API、ColumnLineage 数据结构、entity_relationship 表设计、React Flow 可视化 |
| DataHub | https://github.com/datahub-project/datahub | fineGrainedLineages aspect、sqlglot schema-aware 解析、ManageLineageMenu.tsx 手动编辑交互 |
| OpenLineage | https://github.com/OpenLineage/OpenLineage | ColumnLineageDatasetFacet 规范 (spec/facets/1-0-2/)、TransformationType 枚举 |
| Marquez | https://github.com/MarquezProject/marquez | column-lineage API 设计、Job/Run/Dataset 模型、PostgreSQL 不可变版本化 |
| Spline | https://github.com/AbsaOSS/spline | 属性图 derivesFrom/computedBy 边模型、Spark QueryExecutionListener 方案 |
| Egeria | https://github.com/odpi/egeria | LineageMapping/DataMapping 类型定义 (Model 0770)、三阶段血缘生命周期 |
| Amundsen | https://github.com/amundsen-io/amundsen | Neo4j 双向边存储、Gateway 聚合模式 |
| Apache Atlas | https://github.com/apache/atlas | DataSet→Process→DataSet 有向图模型 |
