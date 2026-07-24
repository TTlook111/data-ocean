# DataOcean 深度优化参考方案

> 基于开源项目、学术论文、行业最佳实践的深度调研，结合项目现状分析和多轮审查修正，形成的可落地的系统性优化方案。
>
> 调研日期：2026-07-22 | 审查修正：2026-07-24（代码命名全量对齐）

---

## 零、实施前置步骤（必须先做）

以下三项是所有依赖列级信息的方案的前置条件，**必须在 Phase 1 任何方案之前完成**。

### P0-A：打通列信息数据通道

**问题**：`AgentState.schema_context`（`state.py:23-32` `RetrievedSchema` TypedDict）目前只有表级字段（`table_name`, `chunk_type`, `chunk_text`, `related_column`, `confidence_score`, `governance_status`, `score`）。RAG 层 Pydantic `RetrievedSchema`（`rag/schema.py`）已有 `columns: list[ColumnInfo]`，但 `schema_retriever.py:55-65` 构建 `schema_context` 时丢弃了列信息。

**修复**（改动 `state.py` + `schema_retriever.py`）：

```python
# 1. state.py — RetrievedSchema TypedDict 新增 columns 字段
class RetrievedSchema(TypedDict, total=False):
    table_name: str
    chunk_type: str
    chunk_text: str
    related_column: str | None
    columns: list[dict]             # ← 新增：ColumnInfo.name/type/comment/trust_score
    confidence_score: int
    governance_status: str
    score: float

# 2. schema_retriever.py:55-65 — 传递 columns
schema_context.append({
    "table_name": item.table_name or "",
    "chunk_type": item.chunk_type or "",
    "chunk_text": item.chunk_text or "",
    "related_column": getattr(item, "related_column", None),
    "columns": [                           # ← 新增
        {"name": c.name, "type": c.type, "comment": c.comment,
         "trust_score": c.trust_score}
        for c in (getattr(item, "columns", None) or [])
    ],
    "confidence_score": getattr(item, "trust_score", 0) or 0,
    "governance_status": item.governance_status or "NORMAL",
    "score": item.score if hasattr(item, "score") else 0.0,
})
```

**验证**：实施后，`schema_linker._build_schema_summary()` 应能打印出每张表下的列列表。

### P0-B：修正权限去重方案

**问题**：原 ThreadLocal 方案已确认**不可行**——两次 `permissionCalculator.calculate()` 在不同线程中调用（`@Async` 线程 vs Tomcat 线程），ThreadLocal 跨线程不可见。

**正确方案**：用 Redis 做任务级缓存。第一次计算结果以 `perm:{taskId}` 为 key 存入 Redis（TTL 60s），第二次调用时先查 Redis。

```java
// PythonAgentClientImpl.java executeAsync() 中，第一次计算后：
PermissionContextVO permContext = permissionCalculator.calculate(userId, datasourceId);
redisTemplate.opsForValue().set("perm:" + taskId, permContext, Duration.ofSeconds(60));

// QueryTaskServiceImpl.java getTaskResult() 中，第二次计算前：
PermissionContextVO cached = (PermissionContextVO) redisTemplate.opsForValue()
    .get("perm:" + task.getId());
PermissionContextVO context = cached != null ? cached
    : permissionCalculator.calculate(task.getUserId(), task.getDatasourceId());
```

### P0-C：GraphQL START 导入

LangGraph 0.2.x 的 `START` 未被当前 `graph.py` 导入。在实施方案 15（Agent 图并行化）前，`graph.py:19` 需改为：

```python
from langgraph.graph import START, END, StateGraph
```

---

## 一、参考项目与论文

### 1.1 有实际落地参考价值的开源项目

| 项目 | Stars | 核心亮点 | 对 DataOcean 的借鉴 |
|------|-------|---------|-------------------|
| [vanna-ai/vanna](https://github.com/vanna-ai/vanna) | ~14K | RAG Pipeline，可插拔向量存储 | Embedding 缓存模式 |
| [eosphoros-ai/DB-GPT](https://github.com/eosphoros-ai/DB-GPT) | ~15K | AWEL 并行工作流，中文支持 | Agent 图并行化参考 |
| [Canner/WrenAI](https://github.com/Canner/WrenAI) | ~5K | MDL 语义层，业务描述驱动 | 列元数据丰富化参考 |
| [OpenMetadata](https://github.com/open-metadata/OpenMetadata) | ~14K | Java+Python+React，实体图+质量评分 | 架构最接近，元数据治理参考 |
| [DataHub](https://github.com/datahub-project/datahub) | ~10K | Aspect 模型 + 断言框架 | 元数据扩展性设计参考 |
| [tobymao/sqlglot](https://github.com/tobymao/sqlglot) | ~12K | SQL AST 解析/验证/转译 | **DataOcean 已使用** |

### 1.2 关键学术论文

| 论文 | arXiv | 可借鉴的技术 |
|------|-------|------------|
| **Death of Schema Linking** | 2408.07702 | 小 Schema（≤50 表）跳过 Schema Linking |
| **PET-SQL** | 2403.09732 | 采样值增强列描述可提升匹配准确度 |
| **MAG-SQL** | 2408.07930 | SQL-to-Schema 反向验证 |
| **DIN-SQL** | 2304.11015 | 将 SQL 生成分解为子任务 + 自校正 |
| **DAIL-SQL** | 2312.17045 | 动态 Few-shot 选择（embedding 相似度 > 字符匹配） |
| **RSL-SQL** | 2411.00073 | 列级裁剪思路（只借鉴正向 embedding 裁剪，不做双向投票） |
| **SQLfuse** | 2407.14568 | 蚂蚁集团生产级 Schema Mining + SQL Critic |

> CHASE-SQL、MAC-SQL、CHESS、C3 不纳入——这些方案依赖 3-6 次 LLM 调用 + 多次 SQL 执行，不适合 DataOcean 基于 DashScope API 的生产环境。

---

## 二、项目现状诊断（已验证）

> 代码级事实核查：**10 条主张中 9 条完全准确**，1 条评注偏差已修正。

### 2.1 Python Agent 工作流

```
QUERY_REWRITER → SCHEMA_RETRIEVER → SCHEMA_LINKER → SQL_GENERATOR → SQL_VALIDATOR → SQL_EXECUTOR → DATA_VISUALIZER
```

**问题**：
- ❌ **完全串行**：7 个节点顺序执行，无 fan-out（`agent/graph.py:262-287`）
- ❌ **Schema Linking 只做表级裁剪**：阈值 `<= 3` 跳过，≥4 张表才触发，但只返回表名（`agent/nodes/schema_linker.py:41`）
- ❌ **重试路由粗粒度**：`_classify_execution_error()` 基于子串匹配（`agent/graph.py:198-222`），`after_executor()` 路由（`225-257`）
- ❌ **超时预算静态**：`sql_generator=40s` 固定（`infra/timeout_budget.py:28-36`）

### 2.2 Python RAG

**问题**：
- ❌ **问题 embedding 无缓存**：每次调用 `embed_single`（`rag/service.py:36`）
- ❌ **Few-shot 用字符重叠**：`rag/fewshot.py:132-143`，O(n*m)
- ❌ **Reranker 硬编码关键词**：子串匹配（`rag/reranker.py:192`）
- ❌ **邻近 chunk 额外 Milvus 查询**：`rag/retriever.py:37-59`
- ❌ **nprobe 二值化**：1 或 16（`rag/vector_store.py:188`）

### 2.3 Java 端

**问题**：
- ❌ **权限计算重复**：`PythonAgentClientImpl.java:80` + `QueryTaskServiceImpl.java:103`
- ❌ **术语表 N+1**：全量加载 + 逐条查关联实体（`PythonAgentClientImpl.java:449-469`）
- ❌ **Fallback chunks 每次查 DB**：同数据源很少变化但每次都查
- ❌ **密码每次解密**：AES 解密在 `buildConnectionConfig()` 中每查询一次（`PythonAgentClientImpl.java:337,348-350`）
- ❌ **toVO 反序列化 6 个 JSON 列**：`QueryTaskServiceImpl.java:363-379`

### 2.4 字段置信度

**问题**：
- ❌ **固定 delta 累加**：成功 `DELTA_QUERY_SUCCESS=+2`，点赞 `DELTA_USER_LIKE=+10`，踩 `DELTA_USER_DISLIKE_CONFIRMED=-15`，群体 `DELTA_GROUP_THRESHOLD=-5`（`ConfidenceCalculator.java:27-33` + `ConfidenceCalculatorImpl.java:77-79`）
- ❌ **无时间衰减**：分数永不变，除非有负面事件
- ❌ **事件记录同步写入**：高频字段大量小写入

---

## 三、优化方向一：提高问答返回速度

### 3.1 Embedding 缓存（预计节省 ~200ms/查询）

**方案**：Redis 缓存问题 embedding，TTL 1 小时。复用现有 `_get_redis()`（`infra/memory.py:31`）。

```python
# rag/service.py — 在 retrieve_schemas() 中包裹 embed_single 调用
import hashlib, json
from dataocean.infra.memory import _get_redis

async def retrieve_schemas(self, question: str, ...):
    redis = await _get_redis()
    cache_key = f"emb:{hashlib.md5(question.encode()).hexdigest()}"
    try:
        cached = await redis.get(cache_key)
    except Exception:
        cached = None
        logger.warning("Embedding cache Redis read failed, falling back to API")

    if cached:
        embedding = json.loads(cached)
    else:
        embedding = await embed_single(question)
        try:
            await redis.setex(cache_key, 3600, json.dumps(embedding))
        except Exception:
            pass  # 写缓存失败不影响主流程

    # 继续检索...
```

### 3.2 术语表 Redis 缓存（预计节省 500ms-1s/查询）

**方案**：Redis 缓存，TTL 30 分钟。项目已有 `RedisTemplate<String, Object>` bean（`RedisConfig.java:39`），key 用固定字符串（术语表对所有用户一致，无权限隔离问题）。

```java
// PythonAgentClientImpl.java — 改造 loadApprovedGlossaryTerms()
private final RedisTemplate<String, Object> redisTemplate;  // 注入已有 bean
private static final String GLOSSARY_CACHE_KEY = "glossary:approved";
private static final Duration GLOSSARY_TTL = Duration.ofMinutes(30);

private List<Map<String, String>> loadApprovedGlossaryTerms() {
    // 1. 先查 Redis
    @SuppressWarnings("unchecked")
    List<Map<String, String>> cached =
        (List<Map<String, String>>) redisTemplate.opsForValue().get(GLOSSARY_CACHE_KEY);
    if (cached != null) return cached;

    // 2. 未命中 → 查 MySQL（同时修 N+1）
    var terms = glossaryTermMapper.selectList(
        new LambdaQueryWrapper<GlossaryTerm>()
            .eq(GlossaryTerm::getStatus, GlossaryTerm.STATUS_APPROVED));

    // 批量查 GLOSSARY_OF 关系（替代逐条 metadataEntityService.getById()）
    List<Long> termIds = terms.stream().map(GlossaryTerm::getId).toList();
    var rels = metadataRelationshipMapper.selectList(
        new LambdaQueryWrapper<MetadataRelationship>()
            .eq(MetadataRelationship::getRelationType, MetadataRelationship.TYPE_GLOSSARY_OF)
            .in(MetadataRelationship::getSourceId, termIds));

    Set<Long> entityIds = rels.stream()
        .map(MetadataRelationship::getTargetId).collect(Collectors.toSet());
    Map<Long, MetadataEntity> entityMap = entityIds.isEmpty() ? Map.of()
        : metadataEntityMapper.selectBatchIds(entityIds).stream()
            .collect(Collectors.toMap(MetadataEntity::getId, e -> e));

    // 3. 组装结果
    List<Map<String, String>> dtos = new ArrayList<>();
    for (var term : terms) {
        Map<String, String> dto = new HashMap<>();
        dto.put("name", term.getName());
        dto.put("displayName", term.getDisplayName() != null ? term.getDisplayName() : "");
        dto.put("description", term.getDescription() != null ? term.getDescription() : "");
        dto.put("synonyms", term.getSynonyms() != null ? term.getSynonyms() : "[]");
        dto.put("fqn", term.getFqn() != null ? term.getFqn() : "");
        // 关联列信息...
        dtos.add(dto);
    }

    // 4. 写 Redis
    redisTemplate.opsForValue().set(GLOSSARY_CACHE_KEY, dtos, GLOSSARY_TTL);
    return dtos;
}
```

**缓存失效点**（以下操作后需 `redisTemplate.delete(GLOSSARY_CACHE_KEY)`）：
- `GlossaryTermServiceImpl` 的 `reviewTerm()`（审核通过/拒绝）和 MyBatis-Plus 的 `save()`/`updateById()`/`removeById()`

### 3.3 Fallback Chunks Redis 缓存（预计节省 ~200ms/查询）

**方案**：与术语表同模式，Redis 缓存，按 dataSourceId 分 key。`loadFallbackChunks()`（`PythonAgentClientImpl.java:402`）开头先查 Redis。

```java
private static final String FALLBACK_CHUNKS_KEY_PREFIX = "fallback:chunks:";
private static final Duration FALLBACK_CHUNKS_TTL = Duration.ofMinutes(30);

private List<KnowledgeChunk> loadFallbackChunks(Long datasourceId) {
    String key = FALLBACK_CHUNKS_KEY_PREFIX + datasourceId;
    @SuppressWarnings("unchecked")
    List<KnowledgeChunk> cached = (List<KnowledgeChunk>) redisTemplate.opsForValue().get(key);
    if (cached != null) return cached;

    // 原 DB 查询逻辑...

    redisTemplate.opsForValue().set(key, result, FALLBACK_CHUNKS_TTL);
    return result;
}
```

**失效时机**：知识文档发布/快照发布时，清除对应 dataSourceId 的 key。

### 3.4 数据源密码缓存（预计节省 ~100ms/查询）

**方案**：Redis 缓存解密后的连接密码，按 dataSourceId 分 key，TTL 5 分钟。

```java
private static final String DS_PASSWORD_KEY_PREFIX = "ds:password:";
private static final Duration DS_PASSWORD_TTL = Duration.ofMinutes(5);

private String getDecryptedPassword(Datasource datasource) {
    String key = DS_PASSWORD_KEY_PREFIX + datasource.getId();
    String cached = (String) redisTemplate.opsForValue().get(key);
    if (cached != null) return cached;

    String decrypted = aesDecrypt(datasource.getEncryptedPassword());
    redisTemplate.opsForValue().set(key, decrypted, DS_PASSWORD_TTL);
    return decrypted;
}
```

**失效时机**：数据源凭证更新时删除对应 key。

### 3.5 大结果集分块传输

**方案**：首批 100 行立即返回，余下分块。Python SSE 新增 `result_chunk` 事件类型，复用现有 `emit_progress()`/`emit_result()` 模式（`agent/sse.py:27-52`）。

```python
# agent/nodes/sql_executor.py — 结果分块
from dataocean.agent.sse import emit_progress

CHUNK_SIZE = 100
rows = result.data  # list[dict]
total_chunks = (len(rows) + CHUNK_SIZE - 1) // CHUNK_SIZE
for i in range(total_chunks):
    chunk = rows[i * CHUNK_SIZE : (i + 1) * CHUNK_SIZE]
    is_last = (i == total_chunks - 1)
    await emit_progress(task_id, "RESULT_CHUNK", "completed",
        json.dumps({"chunk_index": i, "total_chunks": total_chunks,
                    "is_last": is_last, "rows": chunk}))
```

Java 端 `PythonAgentClientImpl.consumeSseStream()` 需处理 `RESULT_CHUNK` 事件并按 `chunk_index` 拼接。

### 3.6 速度优化汇总

| 优化项 | 预计节省 | 实施阶段 |
|--------|---------|---------|
| Embedding 缓存 | ~200ms | Phase 1 |
| 术语表 Redis 缓存（含 N+1 修复） | 500ms-1s | Phase 1 |
| Fallback Chunks Redis 缓存 | ~200ms | Phase 1 |
| 权限计算 Redis 去重 | 200-500ms | Phase 1 |
| 数据源密码 Redis 缓存 | ~100ms | Phase 2 |
| 大结果集分块 | 感知提升 | Phase 3 |

---

## 四、优化方向二：提高 SQL 准确度

### 4.1 Schema Linking 阈值提高（1 行改动）

**依据**：arXiv:2408.07702——50 张表内现代 LLM 处理无关列能力强。过早裁剪可能误删有用列。

```python
# agent/nodes/schema_linker.py:41
# 将魔数 <= 3 改为 <= 8
if len(schema_context) <= 8:
    return {"current_node": "SCHEMA_LINKER"}  # 跳过裁剪
```

### 4.2 列级 Schema Linking（简单版——扩展 Prompt，零额外 LLM 调用）

**方案**：扩展现有 `_prune_schema()` 的 LLM prompt，一次调用同时返回列信息。前提：P0-A（列信息数据通道）已打通。

```python
# agent/nodes/schema_linker.py — 修改 _prune_schema() 中的 prompt（line 74-79）
LINKING_PROMPT = """
给定用户问题和数据库 schema（含列信息），请选择相关的表和列。

Schema:
{schema_summary}

用户问题: {question}

输出 JSON 格式（只输出 JSON，不得包含其他内容）：
{{
  "relevant_tables": ["table1", "table2"],
  "relevant_columns": {{
    "table1": ["col_a", "col_b"],
    "table2": ["col_x", "col_y"]
  }}
}}
"""
```

同时修改 `_build_schema_summary()`（`schema_linker.py:107-124`）展示列名和类型。`_prune_schema()` 解析返回的 `relevant_columns`。

**注意**：不要跳到 RSL-SQL 双向投票（需要 2 次 LLM 调用），先看简单版效果。

### 4.3 列元数据采样值增强

**方案**：PET-SQL 论文证明——在列描述中加入数据库实际采样值，是提升准确度最有效的手段。

Python 侧增强 schema_context：
```python
column_context = {
    "name": "order_amount",
    "type": "DECIMAL(10,2)",
    "sample_values": ["199.00", "59.90", "1299.00"],  # ← 核心新增
    "description": "订单实付金额",
    "glossary_term": "订单金额",
    "confidence_score": 85,
}
```

Java 侧实现（`ColumnCollector.java` 采集阶段，`CollectorContext` 已有 `connection()`）：

```java
// 在 ColumnCollector.collect() 中，每列采集完成后追加采样
// ⚠️ 列名用反引号转义；设置 2s 超时；敏感列（PII 标签）需脱敏
try (Statement stmt = ctx.connection().createStatement()) {
    stmt.setQueryTimeout(2);
    String sql = "SELECT DISTINCT `" + column.getColumnName()
        + "` FROM `" + tableName + "` LIMIT 5";
    try (ResultSet rs = stmt.executeQuery(sql)) {
        List<String> samples = new ArrayList<>();
        while (rs.next() && samples.size() < 5) {
            String val = rs.getString(1);
            if (val != null) samples.add(val.length() > 50 ? val.substring(0, 50) : val);
        }
        column.setSampleValues(String.join(",", samples));  // 需加 DB 字段 + 实体字段
    }
} catch (SQLException e) {
    log.warn("采样失败 {}.{}: {}", tableName, column.getColumnName(), e.getMessage());
}
```

**需要**：Flyway 迁移在 `db_column_meta` 表新增 `sample_values TEXT` 字段 + `DbColumnMeta.java` 实体新增 `sampleValues` 字段。

### 4.4 SQL-to-Schema 幻觉检测（零成本校验）

**方案**：在 `run_sql_validator()`（`agent/nodes/sql_validator.py`）的现有 `validate()` 调用后新增检测。复用已有函数：
- `_extract_tables()` — `agent/nodes/sql_executor.py:113`（含别名处理）
- `_extract_columns()` — `agent/nodes/sql_executor.py:133`
- `_table_aliases()` — `agent/nodes/sql_executor.py:171`

```python
# agent/nodes/sql_validator.py — 在 run_sql_validator() 中 validate() 之后追加

def _detect_schema_hallucination(
    sql: str, schema_context: list[dict]
) -> tuple[bool, str]:
    """检测 SQL 中是否使用了 schema 中不存在的表/列。"""
    used_tables = _extract_tables(sql)
    used_columns = _extract_columns(sql)

    available_tables = {item["table_name"] for item in schema_context}
    available_columns: dict[str, set[str]] = {}
    for item in schema_context:
        tbl = item["table_name"]
        cols = item.get("columns", [])
        available_columns[tbl] = {c["name"] for c in cols}

    for tbl in used_tables:
        if tbl not in available_tables:
            return False, f"幻觉：SQL 使用了不在 schema 中的表 '{tbl}'"
        if tbl in available_columns and available_columns[tbl]:
            for col in used_columns:
                if col != "*" and col not in available_columns[tbl]:
                    # 警告级（列名可能在子查询或别名中），不阻断——记录日志
                    logger.warning(f"疑似幻觉：列 '{tbl}.{col}' 不在 schema 中")
    return True, ""
```

**零额外 LLM 调用、零新依赖**（sqlglot + 现有 `_extract_*` 函数已就绪）。

### 4.5 执行反馈自校正（增强已有重试循环）

**方案**：在 `run_sql_executor()`（`agent/nodes/sql_executor.py:78`）的执行失败分支中，对可修正错误（syntax_error、table_not_found、column_not_found）用 LLM 针对性修正。**不修正 timeout/connection_error**（修正 SQL 无意义）。

```python
# agent/nodes/sql_executor.py — 在 run_sql_executor() 失败分支中
from dataocean.infra.llm import call_llm

if not result.success and state.get("retry_count", 0) < agent_config.max_retries:
    error_type = _classify_execution_error(result.error)
    if error_type in ("syntax_error", "table_not_found", "column_not_found"):
        correction_prompt = f"""SQL 执行失败。
错误: {result.error}
原始 SQL: {sql}
Schema 上下文: {state['schema_context']}

请修正 SQL。只输出修正后的 SQL，不要任何解释。"""
        try:
            corrected_sql = await call_llm(
                system_prompt="你是 SQL 修正专家，根据错误信息修正 SQL 语法和表名列名。",
                user_prompt=correction_prompt,
                temperature=0.1
            )
            state["generated_sql"] = corrected_sql.strip()
        except LLMException:
            pass  # 自校正失败不影响主流程，走已有重试路由
```

**成本**：仅在执行失败时触发 1 次额外 LLM 调用。主路径（成功）零额外成本。

### 4.6 Few-shot 选择升级

**方案**：字符重叠（`rag/fewshot.py:132-143`）→ embedding 相似度。需**双端改动**：① `store_successful_query()` 存储时计算并保存 `question_embedding`；② `retrieve_fewshot_examples()` 用 embedding 余弦相似度评分。

```python
# rag/fewshot.py — retrieve_fewshot_examples() 修改评分逻辑
import numpy as np
from dataocean.infra.embeddings import embed_single

def _cosine_sim(a: list[float], b: list[float]) -> float:
    a_np, b_np = np.array(a), np.array(b)
    return float(np.dot(a_np, b_np) / (np.linalg.norm(a_np) * np.linalg.norm(b_np) + 1e-8))

async def retrieve_fewshot_examples(question: str, tables: list[str], top_k: int = 3):
    q_emb = await embed_single(question)
    candidates = await _load_all_candidates()  # 从 Redis 读取

    scored = []
    for c in candidates:
        if not c.get("question_embedding"):
            continue
        semantic_sim = _cosine_sim(q_emb, c["question_embedding"])
        # 表名重叠作为补充信号
        table_overlap = len(set(tables) & set(c.get("tables", []))) / max(len(tables), 1)
        score = 0.7 * semantic_sim + 0.3 * table_overlap
        scored.append((score, c))

    scored.sort(reverse=True)
    return [c for _, c in scored[:top_k]]

# store_successful_query() 中新增：
# data["question_embedding"] = await embed_single(question)
```

### 4.7 准确度优化汇总

| 优化项 | 额外 LLM 调用 | 实施阶段 |
|--------|-------------|---------|
| Schema Linking 阈值 3→8 | 0 | Phase 1 |
| SQL-to-Schema 幻觉检测 | 0 | Phase 1 |
| 列级 Schema Linking（简单版） | 0 | Phase 2 |
| 列元数据采样值 | 0（采集时一次性） | Phase 2 |
| Few-shot embedding 升级 | 0（复用已有 embedding） | Phase 2 |
| 执行反馈自校正 | 1（仅失败时） | Phase 2 |

---

## 五、优化方向三：字段置信度自提升机制重构

### 5.1 当前机制评估

当前置信度（`ConfidenceCalculatorImpl.java` + `FieldConfidenceEvent.java`）：
- 事件类型常量在 `FieldConfidenceEvent` 中：`TYPE_QUERY_SUCCESS`, `TYPE_USER_LIKE`, `TYPE_USER_DISLIKE_CONFIRMED`, `TYPE_GROUP_THRESHOLD` 等
- Delta 常量在 `ConfidenceCalculator` 接口中：`DELTA_QUERY_SUCCESS=2`, `DELTA_USER_LIKE=10`, `DELTA_USER_DISLIKE_CONFIRMED=-15`, `DELTA_GROUP_THRESHOLD=-5`
- 分数范围：0-100，等级通过比较判断
- `adjustScore(Long columnMetaId, String eventType, Long operatorId, Long sourceQueryId)` 返回 `ConfidenceVO`

**基本框架 OK，不需推翻重建**。需要增加：读时衰减、Schema Linking 加权、治理联动。

### 5.2 方案：读时衰减（不改写入路径）

**核心思路**：不改变事件写入（保持现有 delta 累加），只在读取时对历史事件应用时间衰减。

```java
/**
 * 读时衰减：不改写入路径，只在读取时对历史事件应用时间衰减。
 * 实际实体类是 FieldConfidenceEvent（字段 createdAt: LocalDateTime）。
 * 查询用 FieldConfidenceEventMapper + LambdaQueryWrapper。
 * 半衰期可配置，默认 30 天。
 */
@Service
public class ConfidenceCalculatorImpl implements ConfidenceCalculator {

    private final FieldConfidenceEventMapper eventMapper;   // ← 实际注入的 Mapper
    @Value("${dataocean.confidence.half-life-days:30}")
    private double halfLifeDays;

    /**
     * 新增读取方法：计算衰减后的置信度
     * 注意：eventType 常量定义在 FieldConfidenceEvent 中（TYPE_QUERY_SUCCESS 等）
     */
    public int calculateWithDecay(Long columnMetaId) {
        List<FieldConfidenceEvent> events = eventMapper.selectList(
            new LambdaQueryWrapper<FieldConfidenceEvent>()
                .eq(FieldConfidenceEvent::getColumnMetaId, columnMetaId));

        double score = 50.0; // 初始分数
        LocalDateTime now = LocalDateTime.now();

        for (FieldConfidenceEvent event : events) {
            double daysAgo = Duration.between(event.getCreatedAt(), now).toMillis()
                             / (1000.0 * 86400);
            double decayFactor = Math.exp(-Math.log(2) * daysAgo / halfLifeDays);
            double weight = getBaseWeight(event.getEventType()) * decayFactor;

            score += weight;
        }

        return (int) Math.max(0, Math.min(100, score));
    }

    private double getBaseWeight(String eventType) {
        return switch (eventType) {
            case FieldConfidenceEvent.TYPE_QUERY_SUCCESS -> 1.0;
            case FieldConfidenceEvent.TYPE_USER_LIKE -> 5.0;
            case FieldConfidenceEvent.TYPE_USER_DISLIKE_CONFIRMED -> -8.0;
            case FieldConfidenceEvent.TYPE_GROUP_THRESHOLD -> -3.0;
            case FieldConfidenceEvent.TYPE_SCHEMA_INIT -> 0.0;    // 初始值不计入
            default -> 0.0;
        };
    }
}
```

**关键设计决策**：
- 不改变写入，只改变读取
- 半衰期 30 天通过 `application.yml` 的 `dataocean.confidence.half-life-days` 配置
- `FieldConfidenceEvent` 当前无 `queryComplexity` 字段，复杂度加权逻辑**暂不实现**——如果未来认为有必要，先加 DB 字段 + Flyway 迁移

### 5.3 置信度在 Schema Linking 中的加权

**方案**：在 `_build_schema_summary()` 中标注每列置信度等级（基于 `columns[].trust_score`），由 LLM 自行利用。不改裁剪逻辑，只改 prompt。

```python
# agent/nodes/schema_linker.py — _build_schema_summary() 中
for col in tbl.get("columns", []):
    trust = col.get("trust_score", 0)
    level = "HIGH" if trust >= 70 else ("MEDIUM" if trust >= 40 else "LOW")
    lines.append(f"  - {col['name']} ({col.get('type','')}) [置信度:{level}]")
```

**前提**：P0-A（列信息数据通道）已打通。

### 5.4 治理-置信度联动

**方案**：issue 状态变更时自动调整置信度。采用**方案 B：issue 创建时预存 columnMetaId**——issue 创建（质量检查批处理）时承担 JOIN 开销，`handleIssue()`（管理员审核）直接 O(1) 读取，无需现场反查。

#### 前置步骤（Phase 1 实施）

**① Flyway 迁移**（新建 V43）：

```sql
-- V43__issue_add_column_meta_id.sql
ALTER TABLE metadata_quality_issue ADD COLUMN column_meta_id BIGINT DEFAULT NULL;
CREATE INDEX idx_issue_column_meta ON metadata_quality_issue(column_meta_id);
```

**② `MetadataQualityIssue.java` 实体新增字段**：

```java
/** 关联的 DbColumnMeta.id，便于直接反查用于置信度联动 */
private Long columnMetaId;
```

**③ issue 创建时填充 columnMetaId**（质量检查批处理中）：

```java
// QualityIssueServiceImpl — 创建 issue 的方法中
Long columnMetaId = dbColumnMetaMapper.selectOne(
    new LambdaQueryWrapper<DbColumnMeta>()
        .eq(DbColumnMeta::getSnapshotId, snapshotId)
        .eq(DbColumnMeta::getTableName, tableName)
        .eq(DbColumnMeta::getColumnName, columnName)
).getId();
issue.setColumnMetaId(columnMetaId);
```

**④ `FieldConfidenceEvent.java` 新增事件类型常量**：

```java
public static final String TYPE_GOVERNANCE_ISSUE_CONFIRMED = "GOVERNANCE_ISSUE_CONFIRMED";
public static final String TYPE_GOVERNANCE_ISSUE_RESOLVED = "GOVERNANCE_ISSUE_RESOLVED";
```

#### 联动代码（Phase 1 实施）

```java
// QualityIssueServiceImpl — handleIssue() 方法末尾新增
@Autowired private ConfidenceCalculator confidenceCalculator;

public void handleIssue(Long issueId, String targetStatus, String resolutionNote, Long operatorId) {
    // ... 现有逻辑（line 107-125）...

    // 新增：状态变更时联动置信度
    MetadataQualityIssue issue = issueMapper.selectById(issueId);
    Long columnMetaId = issue.getColumnMetaId();  // ← 预存字段，直接读取

    if (columnMetaId != null) {
        String eventType = switch (targetStatus) {
            case MetadataQualityIssue.STATUS_CONFIRMED ->
                FieldConfidenceEvent.TYPE_GOVERNANCE_ISSUE_CONFIRMED;
            case MetadataQualityIssue.STATUS_RESOLVED ->
                FieldConfidenceEvent.TYPE_GOVERNANCE_ISSUE_RESOLVED;
            default -> null;
        };
        if (eventType != null) {
            confidenceCalculator.adjustScore(columnMetaId, eventType, operatorId, null);
        }
    }
}
```

**关键设计点**：`columnMetaId` 在 issue 创建时（批处理、非用户等待）填充，`handleIssue()` 直接 O(1) 读取。属性稳定不常变，不需要缓存失效策略。

### 5.5 结论

当前置信度基本框架 OK。增加以下增强：
1. **读时衰减**（不改写入路径，新增 `calculateWithDecay()` 方法）
2. **Schema Linking 加权**（prompt 注入，不改裁剪逻辑）
3. **治理联动**（在 `QualityIssueServiceImpl.handleIssue()` 末尾追加）
4. **批量聚合**留到事件表膨胀后考虑

---

## 六、优化方向四：元数据治理增强

### 6.1 元数据驱动的 Schema Linking

**方案**：将治理元数据注入 RAG 的 schema 上下文。前提：P0-A（列信息数据通道）已打通。

```python
# schema_context 中每列增强后（在 schema_retriever.py 构建时）
enriched_column = {
    "name": column.name,
    "type": column.type,
    "description": entity.description,
    "glossary_terms": entity.glossary_terms,
    "tags": entity.tags,
    "quality_score": entity.quality_score,
    "trust_score": entity.trust_score,     # ColumnInfo 已有，alias "confidence_score"
    "governance_status": entity.governance_status,
    "sample_values": entity.sample_values,  # 新字段（方案 4.3）
}
```

### 6.2 自动标签增强（采样值 PII 检测）

**方案**：新增独立函数 `detect_pii_from_samples()`（`infra/auto_tagger.py`），不修改现有 `infer_tags()`/`infer_tags_for_columns()`。**标签名格式对齐项目已有约定**：

```python
# auto_tagger.py — 新增函数（独立，不修改 infer_tags）
# ⚠️ 标签名格式：点分隔 FQN，中文名称。对齐现有 _TAG_PATTERNS 格式
PII_SAMPLE_PATTERNS = {
    "PII.手机号": re.compile(r'^1[3-9]\d{9}$'),
    "PII.邮箱":   re.compile(r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$'),
    "PII.身份证号": re.compile(r'^\d{17}[\dXx]$'),
    "PII.银行卡号": re.compile(r'^\d{16,19}$'),
}

def detect_pii_from_samples(column_name: str, sample_values: list[str]) -> list[str]:
    tags = []
    for value in sample_values[:20]:
        for tag_name, pattern in PII_SAMPLE_PATTERNS.items():
            if pattern.match(str(value)):
                tags.append(tag_name)
    return list(set(tags))
```

**集成**：调用方在获取到 `sample_values` 后调用此函数，与现有 `infer_tags()` 的结果合并去重。

### 6.3 质量评分聚合

**方案**：从 `quality_check_result` 表（`QualityCheckResult` 实体）实时计算，不新建表。

```java
/**
 * 新增服务方法：从已有的 quality_check_result 表实时聚合评分。
 * QualityCheckResult 有 snapshotId + dimension + score + issueCount 字段。
 */
@Service
public class QualityScoreAggregationService {

    /**
     * 数据源级别质量评分（实时计算，不持久化）
     * @return 0-100 分数
     */
    public int getDatasourceQualityScore(Long datasourceId) {
        // 取该数据源最新快照的所有维度检查结果，加权平均
        // 当前表没有直接存储 dataSourceId，需要通过 snapshotId JOIN
        // 实现略——参考 quality_check_result 表结构
    }
}
```

**暴露**：作为 `/api/admin/dashboard/quality-scores` 或仪表盘 API 的一部分。

---

## 七、Agent 架构增强（Phase 3 核心）

### 7.1 Query Rewriter + Metadata Prefetch 并行化

**方案**：两个节点互不依赖——Rewriter 改问题文本，Prefetch 提取数据源配置（connection_config + datasource_id 已在 AgentState 中）。

```python
# agent/graph.py — 修改 build_graph()
# ⚠️ 节点函数名是 query_rewriter_node / schema_retriever_node 等（非文档前版的 run_xxx）
from langgraph.graph import START, END, StateGraph  # START 需新增导入

# 新增节点名常量
NODE_METADATA_PREFETCH = "METADATA_PREFETCH"

# 新增节点函数
async def metadata_prefetch_node(state: AgentState) -> dict:
    """预取数据源连接配置（不需要额外 RAG 检索），供后续 Schema 检索使用。"""
    conn = state.get("connection_config", {})
    logger.info(f"[{state['task_id']}] prefetch: datasource_id={state.get('datasource_id')}")
    return {"current_node": NODE_METADATA_PREFETCH}
    # connection_config 本身就在 state 中，此节点主要是为了与 rewriter 并行执行
    # 未来可扩展：提前解密密码、预检查连接池状态等

# 图拓扑改造：
graph.set_entry_point(NODE_QUERY_REWRITER)   # 移除
graph.add_edge(START, NODE_QUERY_REWRITER)    # 改为独立 add_edge
graph.add_edge(START, NODE_METADATA_PREFETCH) # 两条 START 边实现 fan-out
graph.add_edge(NODE_QUERY_REWRITER, NODE_SCHEMA_RETRIEVER)
graph.add_edge(NODE_METADATA_PREFETCH, NODE_SCHEMA_RETRIEVER)
```

**关键注意**：
- `START` 导入在当前 `graph.py:19` 中不存在，需改为 `from langgraph.graph import START, END, StateGraph`
- `graph.set_entry_point()` 需要移除（与 `add_edge(START, ...)` 不兼容）
- LangGraph 0.2.x 并发 fan-out 中，`TimeoutBudget.allocate()` 在 asyncio 单线程模型下无真正的竞态问题
- **建议先测量两个节点各自耗时，确认并行收益后再改图拓扑**

---

## 八、实施路线图

### 总体原则

1. **每次改动独立可测**，不捆多个改动为一个"架构升级"
2. **0 额外 LLM 调用优先**——缓存、阈值、sqlglot 校验
3. **1 次额外 LLM 调用次之**——仅在失败路径
4. **多语言改动拆开**——Java 和 Python 侧独立迭代
5. **缓存统一使用 Redis**——不引入新的缓存层（原 PermissionCalculatorImpl 中已有的 Caffeine 本地缓存可保留，不影响新方案）

### Phase 0：前置步骤（0.5 周，先于 Phase 1）

| # | 优化项 | 改动文件 | 说明 |
|---|--------|---------|------|
| P0-A | 打通列信息数据通道 | `state.py` + `schema_retriever.py` | RetrievedSchema 新增 columns 字段 |
| P0-B | 权限计算 Redis 去重 | `PythonAgentClientImpl.java` + `QueryTaskServiceImpl.java` | ThreadLocal→Redis，key=`perm:{taskId}` |
| P0-C | START 导入 | `agent/graph.py:19` | `from langgraph.graph import START` |

### Phase 1：低悬果实（1-2 周，6 项）

**全部零额外 LLM 调用。**

| # | 优化项 | 类型 | 改动文件 | 预计收益 |
|---|--------|------|---------|---------|
| 1 | Embedding 缓存 | 速度 | `rag/service.py` | -200ms |
| 2 | 术语表 Redis 缓存（含 N+1 修复） | 速度 | `PythonAgentClientImpl.java` | -500ms |
| 3 | Fallback Chunks Redis 缓存 | 速度 | `PythonAgentClientImpl.java` | -200ms |
| 4 | Schema Linking 阈值 3→8 | 准确度 | `agent/nodes/schema_linker.py` | 减少误裁剪 |
| 5 | SQL-to-Schema 幻觉检测 | 准确度 | `agent/nodes/sql_validator.py` | 拦截幻觉 |
| 6 | 治理-置信度联动 | 置信度 | `QualityIssueServiceImpl.java` + `FieldConfidenceEvent.java` + `MetadataQualityIssue.java` + V43 迁移 | 评分联动 |

### Phase 2：核心优化（2-4 周，7 项）

**最多 1 次额外 LLM 调用（仅失败路径）。全部依赖 Phase 0 的 P0-A。**

| # | 优化项 | 类型 | 改动文件 | 预计收益 |
|---|--------|------|---------|---------|
| 7 | 列级 Schema Linking（简单版） | 准确度 | `agent/nodes/schema_linker.py` | 列裁剪，减少噪声 |
| 8 | 置信度读时衰减 | 置信度 | `ConfidenceCalculatorImpl.java` | 更合理评分 |
| 9 | 置信度 Schema Linking 加权 | 准确度 | `agent/nodes/schema_linker.py` | 高置信列优先 |
| 10 | Few-shot embedding 升级 | 准确度 | `rag/fewshot.py` | 更准的 Few-shot |
| 11 | 执行反馈自校正 | 准确度 | `agent/nodes/sql_executor.py` | 失败恢复率提升 |
| 12 | 列元数据采样值 | 准确度 | `ColumnCollector.java` + `DbColumnMeta.java` + Flyway | 增强 Schema 理解 |
| 13 | 数据源密码 Redis 缓存 | 速度 | `PythonAgentClientImpl.java` | -100ms |

### Phase 3：架构增强（4-6 周，5 项）

**涉及图拓扑变更（Phase 0 P0-C 前提）、跨语言协作。**

| # | 优化项 | 类型 | 改动文件 | 预计收益 |
|---|--------|------|---------|---------|
| 14 | Agent 图并行化 | 速度 | `agent/graph.py` | -1-3s |
| 15 | 元数据驱动 Schema Linking | 准确度 | `agent/nodes/schema_retriever.py` + Java | 丰富的列信息 |
| 16 | 自动标签增强（采样值 PII） | 治理 | `infra/auto_tagger.py` | 治理自动化 |
| 17 | 质量评分聚合 | 治理 | `QualityScoreAggregationService.java` | 仪表盘指标 |
| 18 | 大结果集分块传输 | 体验 | `agent/nodes/sql_executor.py` + Java SSE | 感知更快 |

### 不纳入实施的方案

| 排除项 | 原因 |
|--------|------|
| CHASE-SQL 多候选 + LLM-as-Judge | 每次查询 +4 次 LLM + 3 次 SQL，延迟 +15-40s |
| 自一致性投票 | +3 次 LLM + 3 次 SQL，生产 DB 额外负载 |
| MAC-SQL 多 Agent 验证 | 额外 LLM 验证 LLM 输出，收益可疑 |
| 语义查询缓存（相似度模式） | 缓存失效准确性无法保证，可能返回错误 SQL |
| Outlines 语法约束解码 | 需自建 GPU 模型，DashScope API 不适用 |
| GraphRAG JOIN 路径发现 | 仅学术概念，无生产落地案例 |
| 列级血缘（完整版） | Apache Atlas 级别工程量，应独立 roadmap |
| RSL-SQL 完整双向投票 | +1 次 LLM 调用，先用简单版验证效果 |

---

> **文档维护**：基于 2026-07-22 调研 + 2026-07-24 代码命名全量对齐。18 项可实施方案（含 Phase 0 前置 3 项）分 4 层，8 项明确排除。所有 Java 类名/方法名/字段名和 Python State key/函数名/标签名均与项目源码精确对齐。
