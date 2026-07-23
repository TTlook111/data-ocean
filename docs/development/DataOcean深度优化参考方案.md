# DataOcean 深度优化参考方案

> 基于开源项目、学术论文、行业最佳实践的深度调研，结合项目现状分析和多轮审查修正，形成的可落地的系统性优化方案。
>
> 调研日期：2026-07-22 | 审查修正：2026-07-23

---

## 目录

- [一、参考项目与论文](#一参考项目与论文)
- [二、项目现状诊断（已验证）](#二项目现状诊断已验证)
- [三、优化方向一：提高问答返回速度](#三优化方向一提高问答返回速度)
- [四、优化方向二：提高 SQL 准确度](#四优化方向二提高-sql-准确度)
- [五、优化方向三：字段置信度自提升机制重构](#五优化方向三字段置信度自提升机制重构)
- [六、优化方向四：元数据治理增强](#六优化方向四元数据治理增强)
- [七、实施路线图](#七实施路线图)

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
| [zilliztech/GPTCache](https://github.com/zilliztech/GPTCache) | ~7K | LLM 响应语义缓存 | 可参考其精确匹配缓存思路（非相似度匹配） |

### 1.2 关键学术论文

| 论文 | arXiv | 可借鉴的技术 |
|------|-------|------------|
| **Death of Schema Linking** | 2408.07702 | 小 Schema（≤50 表）跳过 Schema Linking——直接支撑阈值提高 |
| **PET-SQL** | 2403.09732 | 采样值增强列描述可提升匹配准确度 |
| **MAG-SQL** | 2408.07930 | SQL-to-Schema 反向验证——用 sqlglot 提取 SQL 用到的表/列，交叉校验 |
| **DIN-SQL** | 2304.11015 | 将 SQL 生成分解为子任务 + 自校正 |
| **DAIL-SQL** | 2312.17045 | 动态 Few-shot 选择（embedding 相似度 > 字符匹配） |
| **RSL-SQL** | 2411.00073 | 列级裁剪思路——但双向投票成本过高，只借鉴正向 embedding 裁剪 |
| **SQLfuse** | 2407.14568 | 蚂蚁集团生产级 Schema Mining + SQL Critic |

> **注意**：以下论文给出了高准确度数字（Spider ~87%），但其方案依赖 3-6 次 LLM 调用 + 多次 SQL 执行，仅适用于学术基准评测，**不适合 DataOcean 当前基于 DashScope API 的生产环境**。仅列名不纳入方案：CHASE-SQL、MAC-SQL、CHESS、C3。

---

## 二、项目现状诊断（已验证）

> 4 个审查 Agent 对以下诊断进行了代码级事实核查：**10 条主张中 9 条完全准确**，1 条评注偏差已修正。

### 2.1 Python Agent 工作流

```
Query_Rewriter → Schema_Retriever → Schema_Linker → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer
```

**问题**：
- ❌ **完全串行，无并行**：7 个节点严格顺序执行，无 fan-out（`graph.py:262-287`）。Query Rewriter 与数据源元数据预取可以并行
- ❌ **Schema Linking 只做表级裁剪**：不做列级过滤。阈值 `<= 3` 时跳过（即 ≥4 张表才触发），但只返回表名，列信息完全没用到（`schema_linker.py:41`）
- ❌ **重试路由粗粒度**：`table_not_found` 错误回退到 `schema_retriever` 做完整 RAG（昂贵）（`graph.py:198-222`）
- ❌ **超时预算静态分配**：`sql_generator=40s` 固定，不随查询复杂度自适应（`timeout_budget.py:28-36`）

### 2.2 Python RAG

**问题**：
- ❌ **问题 embedding 无缓存**：每次查询都调用 embedding API（~200ms）（`service.py:36`）
- ❌ **Few-shot 相似度用字符重叠**：O(n*m) 字符匹配不精确（`fewshot.py:132-143`）
- ❌ **Reranker 用硬编码关键词**：意图检测基于子串匹配，不支持同义词（`reranker.py:192`）
- ❌ **邻近 chunk 扩展额外 Milvus 查询**：每次检索后同步查询相邻 chunk（`retriever.py:37-59`），增加 1-2 次 RPC
- ❌ **Milvus nprobe 二值化**：只有 1 或 16（`vector_store.py:188`）

### 2.3 Java 端

**问题**：
- ❌ **权限计算重复**：查询前算一次给 Python，结果返回后再算一次做脱敏（`PythonAgentClientImpl.java:80` + `QueryTaskServiceImpl.java:103`）
- ❌ **术语表全量加载 + N+1 查询**：每次查询加载所有已审核术语，并对每个术语逐条查关联实体（`PythonAgentClientImpl.java:93, 449-469`）
- ❌ **Fallback chunks 每次查 DB**：同数据源的 TABLE_DESC chunks 很少变化但每次都查
- ❌ **密码每次解密**：数据源密码 AES 解密每查询一次（`PythonAgentClientImpl.java:337`）
- ❌ **toVO 反序列化 6 个 JSON 列**：列表接口性能差（`QueryTaskServiceImpl.java:363-379`）

### 2.4 字段置信度

**问题**：
- ❌ **计分模型过于简单**：固定 delta 累加（成功+2，点赞+10，踩-15），无频率、衰减、权重考量（`ConfidenceCalculatorImpl.java:77-79`）
- ❌ **无时间衰减**：历史高分字段永远保持 HIGH，即使最近查询频繁失败
- ❌ **事件记录同步写入**：高频字段产生大量小写入

---

## 三、优化方向一：提高问答返回速度

### 3.1 Embedding 缓存（预计节省 ~200ms/查询）

**方案**：Redis 缓存问题 embedding，TTL 1 小时。

```python
# python-service/dataocean/rag/service.py
async def retrieve_with_cache(self, question: str, ...):
    cache_key = f"emb:{hashlib.md5(question.encode()).hexdigest()}"
    cached = await redis.get(cache_key)
    if cached:
        embedding = json.loads(cached)
    else:
        embedding = await embed_single(question)
        await redis.setex(cache_key, 3600, json.dumps(embedding))
    # 继续检索...
```

**复用**：项目已有 `memory.py` 的 Redis 实例（`_get_redis()`），直接调用。
**参考**：Vanna 的向量存储缓存模式。

### 3.2 Query Rewriter 与 Schema 预取并行（预计节省 1-3s）

**方案**：Query Rewriting 与数据源元数据预取互不依赖，可并行。

```python
# graph.py - LangGraph 0.2.x 语法：每条边独立 add_edge
graph.add_node("query_rewriter", run_rewriter)
graph.add_node("metadata_prefetch", run_prefetch)
graph.add_edge(START, "query_rewriter")
graph.add_edge(START, "metadata_prefetch")
graph.add_node("schema_retriever", run_retriever)
graph.add_edge("query_rewriter", "schema_retriever")
graph.add_edge("metadata_prefetch", "schema_retriever")
```

**前提**：Glossary terms 在 Java 端已通过请求体传给 Python，`metadata_prefetch` 做数据源配置提取（密码解密、连接信息），不依赖 Rewriter 输出。
**注意**：建议先测量两个节点各自耗时，确认并行收益后再改图结构。
**参考**：DB-GPT 的 AWEL 并行工作流。

### 3.3 术语表 / Fallback Chunks Redis 缓存（预计节省 500ms-1s/查询）

**方案**：用 `RedisTemplate` 手写缓存（项目已配置 `RedisTemplate<String, Object>`，但 Spring Cache 抽象层后端是 Caffeine 本地缓存，不能直接用 `@Cacheable`）。

```java
// PythonAgentClientImpl.java
private final RedisTemplate<String, Object> redisTemplate; // 已有 Bean
private static final String GLOSSARY_CACHE_KEY = "glossary:approved";
private static final Duration GLOSSARY_TTL = Duration.ofMinutes(30);

private List<GlossaryTermDTO> loadApprovedGlossaryTerms() {
    @SuppressWarnings("unchecked")
    List<GlossaryTermDTO> cached = (List<GlossaryTermDTO>) redisTemplate.opsForValue().get(GLOSSARY_CACHE_KEY);
    if (cached != null) return cached;

    // ⚠️ 先修 N+1：用批量查询替代逐条 entityService.getById()
    List<GlossaryTerm> terms = glossaryTermMapper.selectList(...);
    Set<Long> entityIds = terms.stream().map(GlossaryTerm::getTermOf).collect(Collectors.toSet());
    Map<Long, MetadataEntity> entities = metadataEntityMapper.selectBatchIds(entityIds)
        .stream().collect(Collectors.toMap(MetadataEntity::getId, e -> e));
    // ... 组装 DTO ...

    redisTemplate.opsForValue().set(GLOSSARY_CACHE_KEY, dtos, GLOSSARY_TTL);
    return dtos;
}

// 术语 CRUD 时清除缓存
public void updateTerm(...) {
    // ... 更新逻辑
    redisTemplate.delete(GLOSSARY_CACHE_KEY);
}
```

**关键**：优先修复 N+1 查询，缓存只是加固。
**参考**：OpenMetadata 的元数据缓存层。

### 3.4 权限计算结果请求级缓存（预计节省 200-500ms/查询）

**方案**：第一次计算结果存入 `ThreadLocal`，第二次直接复用。

```java
// ⚠️ 注意：executeAsync 用 @Async 在独立线程中执行，ThreadLocal 天然线程隔离。
// 需要在请求处理完成后调用 clear() 清理。
public class PermissionContext {
    private static final ThreadLocal<Map<Long, PermissionResult>> CACHE = ThreadLocal.withInitial(HashMap::new);

    public static PermissionResult getOrCompute(Long datasourceId, Long userId,
                                                 Supplier<PermissionResult> computer) {
        return CACHE.get().computeIfAbsent(datasourceId, k -> computer.get());
    }

    public static void clear() { CACHE.remove(); }
}
// 在 Filter 或请求结束时调用 PermissionContext.clear();
```

### 3.5 大结果集分块传输

**方案**：首批 100 行立即返回，余下按需加载。

```python
# executor.py - 分块返回
CHUNK_SIZE = 100
for i in range(0, len(results), CHUNK_SIZE):
    yield SSEEvent(type="result_chunk", data=results[i:i+CHUNK_SIZE], chunk_index=i//CHUNK_SIZE)
```

**注意**：需双端配合——Python SSE 新增 `result_chunk` 事件类型，Java `consumeSseStream()` 需处理。

### 3.6 速度优化汇总

| 优化项 | 预计节省 | 难度 | 实施阶段 |
|--------|---------|------|---------|
| Embedding 缓存 | ~200ms | 低 | Phase 1 |
| 术语表 Redis 缓存（先修 N+1） | 500ms-1s | 低 | Phase 1 |
| Fallback Chunks Redis 缓存 | ~200ms | 低 | Phase 1 |
| 权限计算去重 | 200-500ms | 低 | Phase 1 |
| 数据源密码缓存 | 50-100ms | 低 | Phase 2 |
| Rewriter/Schema 预取并行 | 1-3s | 中 | Phase 2 |
| 大结果集分块 | 感知提升 | 中 | Phase 3 |

---

## 四、优化方向二：提高 SQL 准确度

### 4.1 Schema Linking 阈值提高（1 行改动）

**依据**：论文 "Death of Schema Linking"（arXiv:2408.07702）——对于 50 张表以内的 schema，现代 LLM 处理无关列的能力很强。过早裁剪反而可能误删有用列。

```python
# schema_linker.py
SCHEMA_LINKING_THRESHOLD = 8  # 从 <=3 改为 <=8 才跳过
```

### 4.2 列级 Schema Linking（简单版——一次 LLM 同时裁剪表+列）

**现状**：`schema_linker.py` 只让 LLM 返回 `relevant_tables`，列信息完全没用到。
**方案**：扩展 prompt，让 LLM **一次调用**同时返回相关表和列，不增加 LLM 调用次数。

```python
# schema_linker.py - 简单版列级裁剪（零额外 LLM 调用）
LINKING_PROMPT = """
给定用户问题和数据库 schema，请选择相关的表和列。

Schema:
{schema_context}

用户问题: {question}

输出 JSON 格式：
{{
  "relevant_tables": ["table1", "table2"],
  "relevant_columns": {{
    "table1": ["col_a", "col_b"],
    "table2": ["col_x", "col_y"]
  }}
}}
"""
```

**注意**：不要从简单版直接跳到 RSL-SQL 双向投票（需要两次 LLM 调用），先看简单版效果再决定是否升级。
**参考**：RSL-SQL 的列级裁剪思路，但简化为单次 LLM 调用。

### 4.3 列元数据采样值增强

**方案**：PET-SQL 论文证明——在列描述中加入数据库实际采样值，是提升准确度最有效的手段。只加这一个字段，其他字段（formula、描述等）有数据源时再加。

```python
# RAG 检索时，列上下文增强：
column_context = {
    "name": "order_amount",
    "type": "DECIMAL(10,2)",
    "sample_values": ["199.00", "59.90", "1299.00"],  # ← 核心新增：SELECT ... LIMIT 5 采样
    "description": "订单实付金额",                       # 已有（元数据治理描述）
    "glossary_term": "订单金额",                         # 已有（术语表）
    "confidence_score": 85,                              # 已有（置信度）
}
```

**实现**：元数据采集阶段对每列执行 `SELECT DISTINCT column FROM table LIMIT 5`，结果缓存到元数据表。
**参考**：PET-SQL（arXiv:2403.09732），采样值可提升准确度 5-15%（在 Spider 基准上）。

### 4.4 SQL-to-Schema 幻觉检测（零成本校验）

**方案**：生成 SQL 后，用 sqlglot 提取实际使用的表/列，与 Schema 上下文交叉验证。检测 LLM 是否幻觉出了不存在的表或列。

```python
# 在 sql_validator 节点中增加
def validate_schema_usage(sql: str, schema_context: SchemaContext) -> ValidationResult:
    from sqlglot import exp
    parsed = exp.parse(sql)[0]
    used_tables = {t.name for t in parsed.find_all(exp.Table)}
    used_cols = {c.name for c in parsed.find_all(exp.Column)}

    available_tables = {t.name for t in schema_context.tables}
    hallucinated_tables = used_tables - available_tables

    if hallucinated_tables:
        return ValidationResult(
            valid=False,
            error=f"幻觉检测：SQL 使用了不在 schema 中的表 {hallucinated_tables}",
            suggestion="请仅使用已识别的表"
        )
```

**零额外 LLM 调用、零新依赖**（sqlglot 已集成）。
**参考**：MAG-SQL（arXiv:2408.07930）的 SQL-to-Schema 反向验证。

### 4.5 执行反馈自校正（增强已有重试循环）

**现状**：项目已有 `after_executor` 重试路由（`graph.py:198-222`），但错误分类基于子串匹配（`_classify_execution_error`）。
**方案**：将执行错误信息（包括错误类型 + 原 SQL + Schema 上下文）结构化反馈给 LLM，让 LLM 做针对性修正，而非简单标记 `retry_count++` 后回到 generator。

```python
# executor 节点增强——仅在执行失败时触发 1 次额外 LLM 调用
async def execute_with_feedback(state, llm):
    sql = state["sql"]
    result = await execute_sql(sql)

    if result.error and state.get("retry_count", 0) < 2:
        correction_prompt = f"""
        SQL 执行失败：
        错误信息：{result.error}
        原始 SQL：{sql}
        Schema：{state['schema_context']}

        请修正 SQL。只输出修正后的 SQL，不要解释。
        """
        corrected_sql = await llm.invoke(correction_prompt)
        return {"generated_sql": corrected_sql, "retry_count": state.get("retry_count", 0) + 1}
```

**成本**：仅在首次执行失败时触发 1 次额外 LLM 调用，主路径（执行成功）零额外成本。
**参考**：DIN-SQL 的自校正步骤，e-SQL 的 Execute-Then-Debug 模式。

### 4.6 Few-shot 选择升级

**方案**：当前字符重叠相似度（O(n*m)）用 embedding 相似度替换。复用已有 embedding API。

```python
# fewshot.py
async def select_fewshot(question: str, candidates: list, top_k: int = 3):
    q_emb = await embed_single(question)
    scored = []
    for c in candidates:
        semantic_sim = cosine_similarity(q_emb, c.question_embedding)
        structural_sim = sql_skeleton_similarity(c.sql)  # SQL 骨架相似度
        score = 0.6 * semantic_sim + 0.4 * structural_sim
        scored.append((score, c))
    scored.sort(reverse=True)
    return [c for _, c in scored[:top_k]]
```

**参考**：DAIL-SQL 的动态 Few-shot 选择——embedding 相似度 + 结构相似度混合评分。

### 4.7 准确度优化汇总

| 优化项 | 额外 LLM 调用 | 难度 | 实施阶段 |
|--------|-------------|------|---------|
| Schema Linking 阈值 3→8 | 0 | 低 | Phase 1 |
| SQL-to-Schema 幻觉检测 | 0 | 低 | Phase 1 |
| 列级 Schema Linking（简单版） | 0 | 中 | Phase 2 |
| 列元数据采样值 | 0（采集时一次性） | 中 | Phase 2 |
| Few-shot embedding 升级 | 0（复用已有 embedding） | 中 | Phase 2 |
| 执行反馈自校正 | 1（仅失败时） | 中 | Phase 2 |

---

## 五、优化方向三：字段置信度自提升机制重构

### 5.1 当前机制评估

当前置信度（`ConfidenceCalculatorImpl.java`）：
```
成功查询 +2, 点赞 +10, 确认踩 -15, 群体阈值 -5
分数范围：0-100，等级：HIGH(80+), MEDIUM(50-79), LOW(25-49), VERY_LOW(0-24)
```

**合理性**：简单可解释、事件驱动、分数 0-100 直观。**基本框架 OK，不需推翻重建**。

**需要增强的 4 个点**：
1. ⚠️ 无时间衰减——"一劳永逸"，历史高分永远保持
2. ⚠️ 固定 delta——不区分"重要查询成功"和"简单查询成功"
3. ⚠️ 高频事件产生大量 DB 写入
4. ⚠️ 置信度没充分利用——只在 reranker 中加 0.1 分

### 5.2 方案：读时衰减（不改写入路径）

**核心思路**：不改变事件写入逻辑（保持简单 delta 累加），只在 `getByColumnMetaId()` 读取时计算衰减。

```java
/**
 * 读时衰减：不改写入路径，只在读取时对历史事件应用时间衰减。
 * 半衰期可配置，默认 30 天。
 */
public int calculateWithDecay(Long columnMetaId) {
    List<ConfidenceEvent> events = eventMapper.selectByColumnMetaId(columnMetaId);

    double score = 50.0; // 初始分数
    double now = System.currentTimeMillis();
    double halfLifeMs = TimeUnit.DAYS.toMillis(halfLifeDays);

    for (ConfidenceEvent event : events) {
        double daysAgo = (now - event.getTimestamp()) / (1000.0 * 86400);
        double decayFactor = Math.exp(-Math.log(2) * daysAgo / halfLifeDays);
        double weight = getBaseWeight(event.getType()) * decayFactor;

        // 查询复杂度加权（可选，从 event 中读取复杂度字段）
        if (event.getQueryComplexity() != null) {
            weight *= (1 + event.getQueryComplexity() * 0.1);
        }

        score += weight;
    }

    return (int) Math.max(0, Math.min(100, score));
}
```

**关键设计决策**：
- 不改变写入（保持现有 delta 累加），只改变读取
- 半衰期 30 天可配置
- 查询复杂度加权可选——需要先记录查询复杂度数据，初期可跳过

### 5.3 置信度在链路中的渗透

**现状**：置信度仅在 reranker 中加 0.1 分（几乎没影响）。

**方案**：置信度在 Schema Linking 阶段发挥实质性作用——高置信列在 embedding 匹配中加权优先：

```python
# schema_linker.py - 置信度加权
def forward_prune(question: str, columns: list):
    scores = []
    for col in columns:
        # embedding 相似度为基础
        base = cosine_similarity(q_emb, col.embedding)
        # 置信度加权（HIGH: 1.2x, MEDIUM: 1.0x, LOW: 0.8x）
        confidence_boost = {80: 1.2, 50: 1.0, 25: 0.8}.get(col.confidence // 25 * 25, 1.0)
        scores.append((base * confidence_boost, col))
    # ...
```

### 5.4 治理-置信度联动

```java
// GovernanceIssueService — issue 状态变化时自动触发置信度调整
@EventListener
public void onIssueStatusChanged(IssueStatusChangedEvent event) {
    if (event.getNewStatus() == IssueStatus.CONFIRMED) {
        confidenceCalculator.adjust(event.getColumnId(), GOVERNANCE_ISSUE, -5);
    } else if (event.getNewStatus() == IssueStatus.RESOLVED) {
        confidenceCalculator.adjust(event.getColumnId(), QUALITY_CHECK_PASS, +3);
    }
}
```

### 5.5 结论

当前置信度基本框架 OK。需要增加：
1. **读时衰减**（不改写入路径，最小改动）
2. **Schema Linking 阶段加权**（利用已有数据）
3. **治理联动**（已有事件基础设施，新增一个 Listener）
4. **批量聚合**留到事件表真正膨胀时再考虑

---

## 六、优化方向四：元数据治理增强

### 6.1 元数据驱动的 Schema Linking

**方案**：将治理元数据（描述、标签、术语、质量分数、置信度）注入 RAG 的 schema 上下文，让 LLM 看到更丰富的列信息。

```python
# schema_context 增强
enriched_column = {
    "name": column.name,
    "type": column.type,
    "description": entity.description,           # 来自元数据治理
    "glossary_terms": entity.glossary_terms,      # 来自术语表
    "tags": entity.tags,                          # 来自分类标签
    "quality_score": entity.quality_score,        # 来自质量检查
    "confidence_score": entity.confidence_score,  # 来自置信度
    "governance_status": entity.governance_status, # 治理状态
    "sample_values": entity.sample_values,        # 采样值
}
```

**注意**：`sample_values` 是新字段（见 4.3 节），其余字段均已存在于 Java 侧的元数据治理体系中，只需在构建 Python 请求体时传递。
**参考**：WrenAI 的 MDL 语义层——丰富列描述直接提升准确度。

### 6.2 自动标签增强

**现状**：Python `AutoTagger` 基于列名模式匹配。
**增强**（低风险、单文件改动）：

1. **基于采样值推断**：检测列值中的 PII（手机号正则、邮箱正则、身份证校验）
2. **基于查询模式推断**：WHERE 条件高频列的自动标记（需要分析 SQL 日志，Phase 3）

```python
# auto_tagger.py — 采样值 PII 检测
PII_PATTERNS = {
    "PII:phone": re.compile(r'^1[3-9]\d{9}$'),
    "PII:email": re.compile(r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$'),
    "PII:id_card": re.compile(r'^\d{17}[\dXx]$'),
}

def detect_pii_from_samples(column_name: str, sample_values: list[str]) -> list[str]:
    tags = []
    for value in sample_values[:20]:  # 最多检查 20 个采样值
        for tag_name, pattern in PII_PATTERNS.items():
            if pattern.match(str(value)):
                tags.append(tag_name)
    return list(set(tags))
```

**参考**：OpenMetadata 的 Auto-Classification。

### 6.3 质量评分聚合

**方案**：列的质量检查结果（`quality_check_result` 表已有）自动聚合为表/数据源评分。

```sql
-- 表评分 = AVG(列评分)，其中列评分基于最近的质量检查结果
-- 数据源评分 = AVG(表评分)
```

这是一个独立的轻量计算逻辑，不需要引入新表或新依赖。

---

## 七、实施路线图

### 总体原则

1. **每次改动独立可测**，不捆多个改动为一个"架构升级"
2. **0 额外 LLM 调用优先**——缓存、阈值、sqlglot 校验都不增加 API 调用
3. **1 次额外 LLM 调用次之**——仅在失败路径或低频路径增加
4. **多语言改动拆开**——Java 侧优化和 Python 侧优化独立迭代

### Phase 1：立即可做（1-2 周，7 项）

**全部零额外 LLM 调用，单文件改动为主。**

| # | 优化项 | 类型 | 改动文件 | 预计收益 |
|---|--------|------|---------|---------|
| 1 | Embedding 缓存 | 速度 | `rag/service.py` | -200ms |
| 2 | 术语表 Redis 缓存（先修 N+1） | 速度 | `PythonAgentClientImpl.java` | -500ms |
| 3 | Fallback Chunks Redis 缓存 | 速度 | `PythonAgentClientImpl.java` | -200ms |
| 4 | 权限计算去重 | 速度 | `QueryTaskServiceImpl.java` | -300ms |
| 5 | Schema Linking 阈值 3→8 | 准确度 | `schema_linker.py` | 减少误裁剪 |
| 6 | SQL-to-Schema 幻觉检测 | 准确度 | `sandbox/validator.py` | 拦截幻觉 |
| 7 | 治理-置信度联动 | 置信度 | Java EventListener | 评分更准确 |

### Phase 2：短期优化（2-4 周，7 项）

**最多 1 次额外 LLM 调用（仅在失败路径），需要 Java+Python 两端配合。**

| # | 优化项 | 类型 | 改动文件 | 预计收益 |
|---|--------|------|---------|---------|
| 8 | 列级 Schema Linking（简单版） | 准确度 | `schema_linker.py` | 列裁剪，减少噪声 |
| 9 | 置信度读时衰减 | 置信度 | `ConfidenceCalculatorImpl.java` | 更合理评分 |
| 10 | 置信度 Schema Linking 加权 | 准确度 | `schema_linker.py` | 高置信列优先 |
| 11 | Few-shot embedding 升级 | 准确度 | `rag/fewshot.py` | 更准的 Few-shot |
| 12 | 执行反馈自校正 | 准确度 | `agent/graph.py` | 失败恢复率提升 |
| 13 | 列元数据采样值 | 准确度 | Java metadata + Python | 增强 Schema 理解 |
| 14 | 数据源密码缓存 | 速度 | `PythonAgentClientImpl.java` | -100ms |

### Phase 3：架构增强（4-6 周，5 项）

**涉及 Agent 图拓扑变更、跨语言协作。**

| # | 优化项 | 类型 | 改动文件 | 预计收益 |
|---|--------|------|---------|---------|
| 15 | Agent 图并行化（Rewriter + Prefetch） | 速度 | `agent/graph.py` | -1-3s |
| 16 | 元数据驱动 Schema Linking | 准确度 | Java + Python | 更丰富的列信息 |
| 17 | 自动标签增强（采样值 PII 检测） | 治理 | `auto_tagger.py` | 治理自动化 |
| 18 | 质量评分聚合 | 治理 | Java metadata | 表级/数据源级评分 |
| 19 | 大结果集分块传输 | 体验 | `executor.py` + Java SSE | 感知更快 |

### 不纳入实施的方案

以下方案经过审查后**明确不纳入**当前阶段的实施计划：

| 排除项 | 原因 |
|--------|------|
| CHASE-SQL 多候选 + LLM-as-Judge | 每次查询增加 4 次 LLM API 调用 + 3 次 SQL 执行，总延迟 +15-40s |
| 自一致性投票 | 3 次 LLM + 3 次 SQL 执行，生产数据库额外负载 |
| MAC-SQL 多 Agent 验证 | 额外 LLM 验证 LLM 输出，收益可疑 |
| 语义查询缓存（GPTCache 相似度模式） | 缓存失效准确性无法保证，可能返回错误 SQL |
| Outlines 语法约束解码 | 需自建 GPU 模型，当前依赖 DashScope API 不可行 |
| GraphRAG JOIN 路径发现 | 仅理论/学术概念，无实际公司落地案例 |
| 列级血缘（完整版） | Apache Atlas 级别工程量，应作为独立 roadmap 项目 |
| RSL-SQL 完整双向投票 | 增加 1 次额外 LLM 调用，先用简单版（4.2）验证效果 |

> **文档维护**：本文档基于 2026-07-22 的调研和 2026-07-23 的审查修正编写。19 项可实施方案分 3 个 Phase，8 项明确排除。
