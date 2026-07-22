# DataOcean 深度优化参考方案

> 基于 4 个并行研究 Agent 对开源项目、学术论文、行业最佳实践的深度调研，结合项目现状分析，形成的系统性优化方案。
>
> 调研日期：2026-07-22

---

## 目录

- [一、调研覆盖的开源项目与论文](#一调研覆盖的开源项目与论文)
- [二、项目现状诊断](#二项目现状诊断)
- [三、优化方向一：提高问答返回速度](#三优化方向一提高问答返回速度)
- [四、优化方向二：提高 SQL 准确度](#四优化方向二提高-sql-准确度)
- [五、优化方向三：字段置信度自提升机制重构](#五优化方向三字段置信度自提升机制重构)
- [六、优化方向四：元数据治理增强](#六优化方向四元数据治理增强)
- [七、优化方向五：Agent 架构升级](#七优化方向五agent-架构升级)
- [八、实施优先级与路线图](#八实施优先级与路线图)
- [九、参考项目与论文索引](#九参考项目与论文索引)

---

## 一、调研覆盖的开源项目与论文

### 1.1 NL2SQL 开源框架

| 项目 | Stars | 架构模式 | 核心亮点 |
|------|-------|---------|---------|
| [vanna-ai/vanna](https://github.com/vanna-ai/vanna) | ~14K | RAG Pipeline | 最简单的 NL2SQL API，可插拔向量存储 |
| [eosphoros-ai/DB-GPT](https://github.com/eosphoros-ai/DB-GPT) | ~15K | Multi-Agent + RAG | AWEL 工作流引擎，多 Agent 协作，中文支持好 |
| [Canner/WrenAI](https://github.com/Canner/WrenAI) | ~5K | Semantic Layer (MDL) + LLM | **建模定义语言**，业务语义层直接提升准确度 |
| [defog-ai/sqlcoder](https://github.com/defog-ai/sqlcoder) | ~9K | Fine-tuned Model | 开源 NL2SQL 专用模型，34B 参数 |
| [Dataherald/dataherald](https://github.com/Dataherald/dataherald) | ~2K | Enterprise NL2SQL | 企业级，幻觉抑制 |
| [BeaverAI/MAC-SQL](https://github.com/BeaverAI/MAC-SQL) | ~1K | Multi-Agent Pipeline | 学术验证的多 Agent 协作模式 |
| [RUCK-NLP/DAIL-SQL](https://github.com/RUCK-NLP/DAIL-SQL) | ~500 | Dynamic ICL | 动态 few-shot 选择，Spider 86.6% |
| [tobymao/sqlglot](https://github.com/tobymao/sqlglot) | ~12K | SQL AST 工具 | DataOcean 已使用，30+ 方言转译 |

### 1.2 元数据治理平台

| 项目 | Stars | 核心能力 | 对 DataOcean 的借鉴价值 |
|------|-------|---------|----------------------|
| [OpenMetadata](https://github.com/open-metadata/OpenMetadata) | ~14K | 实体图模型 + 质量评分 + 术语表 + 自动分类 | **最接近 DataOcean 架构**，Java+Python+React |
| [DataHub](https://github.com/datahub-project/datahub) | ~10K | Aspect 模型 + GraphQL + 断言框架 | 最可扩展的元数据架构 |
| [Apache Atlas](https://github.com/apache/atlas) | ~2K | 类型系统 + 列级血缘 + 分类传播 | 最成熟的列级血缘实现 |
| [Marquez/OpenLineage](https://github.com/MarquezProject/marquez) | ~1K | 血缘标准 + 事件驱动 | 行业标准血缘格式 |

### 1.3 关键学术论文

| 论文 | arXiv | 核心技术 | 准确度 |
|------|-------|---------|-------|
| **RSL-SQL** | 2411.00073 | 双向 Schema Linking + 双模式投票 | 94% 召回，83% 列裁剪 |
| **CHESS** | 2405.16755 | 多 Agent Schema 剪枝 + 单元测试验证 | Spider ~87% |
| **MAC-SQL** | 2312.11242 | Selector Agent 做列级评分 | Spider ~87% |
| **MAG-SQL** | 2408.07930 | Soft Schema Linking + SQL-to-Schema | 列级链接优化 |
| **PET-SQL** | 2403.09732 | Reference-Enhanced + 采样单元值 | Spider 87.6% (SOTA) |
| **DIN-SQL** | 2304.11015 | 分解式 In-Context Learning | Spider ~86% |
| **C3** | 2307.07306 | Clear Prompting + 校准 + 一致性 | Spider 82.3% |
| **SQLfuse** | 2407.14568 | Schema Mining + SQL Critic（蚂蚁集团） | 生产级 |
| **TA-SQL** | 2405.15307 | Task Alignment 幻觉缓解 | BIRD +21.23% |
| **Death of Schema Linking** | 2408.07702 | 小 schema 跳过 Schema Linking | 颠覆性发现 |
| **CHASE-SQL** | 2410.01711 | 多候选生成 + LLM-as-Judge 排名 | Spider/BIRD SOTA |
| **Solid-SQL** | 2412.12522 | 数据增强训练鲁棒 Schema Linking | 扰动基准 +11.6% |

---

## 二、项目现状诊断

基于对项目源码的深度分析，以下是当前各模块的瓶颈定位：

### 2.1 Python Agent 工作流

```
Query_Rewriter → Schema_Retriever → Schema_Linker → SQL_Generator → SQL_Validator → SQL_Executor → Data_Visualizer
```

**问题**：
- ❌ **完全串行，无并行**：7 个节点严格顺序执行，无 fan-out（`graph.py:262-287`）
- ❌ **Schema Linking 阈值过低**：`len(schema_context) > 3` 才触发 LLM 调用（`schema_linker.py:41`），大多数查询 3-5 张表时直接跳过
- ❌ **Schema Linking 只做表级裁剪**：不做列级过滤，无关列仍进入 SQL 生成上下文
- ❌ **重试路由粗粒度**：`table_not_found` 错误回退到 `schema_retriever` 做完整 RAG（昂贵），而非直接注入正确表名
- ❌ **超时预算静态分配**：`sql_generator=40s` 固定，不随查询复杂度自适应（`timeout_budget.py:29-36`）

### 2.2 Python RAG

**问题**：
- ❌ **邻近 chunk 扩展额外 Milvus 查询**：每次检索后同步查询相邻 chunk（`retriever.py:37-59`），增加 1-2 次 RPC
- ❌ **Few-shot 相似度用字符重叠**：`fewshot.py:132-143` 的 O(n*m) 字符匹配不精确
- ❌ **问题 embedding 无缓存**：每次查询都调用 embedding API（~200ms）
- ❌ **Reranker 用硬编码关键词**：意图检测基于子串匹配（`reranker.py:192`），不支持同义词
- ❌ **Milvus nprobe 二值化**：只有 1 或 16，中间数据集没有合适的召回率（`vector_store.py:188`）

### 2.3 Java 端

**问题**：
- ❌ **权限计算重复**：查询前算一次给 Python，结果返回后再算一次做脱敏（`PythonAgentClientImpl.java:80` + `QueryTaskServiceImpl.java:103`）
- ❌ **术语表全量加载**：每次查询加载所有已审核术语（`PythonAgentClientImpl.java:93`），N+1 查询
- ❌ **Fallback chunks 每次查 DB**：同数据源的 TABLE_DESC chunks 很少变化但每次都查
- ❌ **密码每次解密**：数据源密码 AES 解密每查询一次（`PythonAgentClientImpl.java:337`）
- ❌ **结果序列化双重转换**：JSON 先读出再逐字段序列化回去（`QueryTaskServiceImpl.java:202-255`）
- ❌ **toVO 反序列化 6 个 JSON 列**：列表接口性能差（`QueryTaskServiceImpl.java:363-379`）

### 2.4 字段置信度

**问题**：
- ❌ **计分模型过于简单**：固定 delta 累加（成功+2，点赞+10，踩-15），无频率、衰减、权重考量（`ConfidenceCalculatorImpl.java:77-79`）
- ❌ **无时间衰减**：分数永远不降，除非有负面事件
- ❌ **事件记录同步写入**：高频字段产生大量小写入

---

## 三、优化方向一：提高问答返回速度

### 3.1 Embedding 缓存（预计节省 200ms/查询）

**现状**：每次查询调用 `embed_single`，约 200ms。
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

**参考**：Vanna 的向量存储缓存模式。

### 3.2 Query Rewriter 与 Schema 预取并行（预计节省 1-3s）

**现状**：串行执行 Query_Rewriter → Schema_Retriever。
**方案**：Query Rewriting 和数据源元数据预取可以并行。

```python
# graph.py - 改为并行节点
graph.add_node("query_rewriter", run_rewriter)
graph.add_node("schema_prefetch", run_prefetch)  # 预取数据源配置、glossary terms
graph.add_edge(START, ["query_rewriter", "schema_prefetch"])  # 并行启动
graph.add_node("schema_retriever", run_retriever)
graph.add_edge(["query_rewriter", "schema_prefetch"], "schema_retriever")  # 两者完成后继续
```

**参考**：DB-GPT 的 AWEL 并行工作流。

### 3.3 术语表 / Fallback Chunks Redis 缓存（预计节省 500ms-1s/查询）

**现状**：每次查询加载全部已审核术语 + TABLE_DESC chunks。
**方案**：

```java
// Java 端 - Redis 缓存
@Cacheable(value = "glossary:approved", key = "#datasourceId", unless = "#result.isEmpty()")
public List<GlossaryTermDTO> loadApprovedGlossaryTerms(Long datasourceId) { ... }

@Cacheable(value = "fallback:chunks", key = "#datasourceId")
public List<KnowledgeChunk> loadFallbackChunks(Long datasourceId) { ... }

// 术语 CRUD 时清除缓存
@CacheEvict(value = "glossary:approved", allEntries = true)
public void updateTerm(...) { ... }
```

**参考**：OpenMetadata 的元数据缓存层。

### 3.4 权限计算结果请求级缓存（预计节省 200-500ms/查询）

**现状**：查询前后各算一次权限。
**方案**：第一次计算结果存入请求上下文，第二次直接复用。

```java
// ThreadLocal 或 RequestScope 缓存
public class PermissionContext {
    private static final ThreadLocal<Map<Long, PermissionResult>> CACHE = ThreadLocal.withInitial(HashMap::new);

    public static PermissionResult getOrCompute(Long datasourceId, Long userId, Supplier<PermissionResult> computer) {
        return CACHE.get().computeIfAbsent(datasourceId, k -> computer.get());
    }

    public static void clear() { CACHE.remove(); }
}
```

### 3.5 SQL 生成超时预算自适应

**现状**：固定 40s。
**方案**：根据 schema 复杂度和历史查询耗时动态调整。

```python
# timeout_budget.py
def allocate_sql_generator_budget(schema_size: int, history_avg: float) -> float:
    base = 20  # 基础 20s
    schema_factor = min(schema_size * 2, 20)  # 每张表 +2s，上限 20s
    history_factor = min(history_avg * 0.5, 10)  # 历史均值的 50%，上限 10s
    return min(base + schema_factor + history_factor, 60)  # 总上限 60s
```

### 3.6 大结果集分块传输

**现状**：10K 行一次性返回，SSE 事件巨大。
**方案**：首批 100 行立即返回，余下按需加载。

```python
# executor.py - 分块返回
CHUNK_SIZE = 100
for i in range(0, len(results), CHUNK_SIZE):
    yield SSEEvent(type="result_chunk", data=results[i:i+CHUNK_SIZE], chunk_index=i//CHUNK_SIZE)
```

### 3.8 语义查询缓存（GPTCache 模式）

**现状**：每次查询都走完整 RAG + LLM 链路。
**方案**：缓存 (问题 SQL) 对，新问题通过 embedding 相似度匹配已缓存查询。

**参考项目**：[zilliztech/GPTCache](https://github.com/zilliztech/GPTCache)

```python
# rag/service.py - 语义缓存层
class SemanticQueryCache:
    """语义查询缓存：相似问题复用已生成的 SQL"""

    def __init__(self, milvus_client, redis_client):
        self.milvus = milvus_client  # 存储问题 embedding
        self.redis = redis_client     # 存储 SQL 结果

    async def get_or_generate(self, question: str, datasource_id: int, generator):
        q_emb = await embed_single(question)

        # Milvus 检索相似问题（阈值 0.92）
        similar = await self.milvus.search(
            collection="query_cache",
            query_vector=q_emb,
            top_k=1,
            filter=f"datasource_id == {datasource_id}"
        )

        if similar and similar[0]["distance"] > 0.92:
            cached_sql = await self.redis.get(f"sql:{similar[0]['id']}")
            if cached_sql:
                return {"sql": cached_sql, "from_cache": True, "cache_similarity": similar[0]["distance"]}

        # 缓存未命中，生成新 SQL
        result = await generator()

        # 写入缓存
        cache_id = str(uuid.uuid4())
        await self.milvus.insert("query_cache", [{"id": cache_id, "vector": q_emb, "datasource_id": datasource_id}])
        await self.redis.setex(f"sql:{cache_id}", 3600, result["sql"])

        return {**result, "from_cache": False}

    async def invalidate(self, datasource_id: int):
        """Schema 变更时清除该数据源的缓存"""
        await self.milvus.delete("query_cache", filter=f"datasource_id == {datasource_id}")
```

**适用条件**：同一数据源、高相似度问题（阈值 > 0.92）、Schema 未变更。

### 3.9 语法约束解码（Outlines 模式）

**方案**：在 SQL 生成时用 CFG/BNF 语法约束 LLM 的 token 生成，从源头防止格式错误的 SQL。

**参考项目**：[dottxt-ai/outlines](https://github.com/dottxt-ai/outlines)

```python
# 使用 outlines 约束 SQL 生成格式
import outlines

@outlines.prompt
def sql_prompt(schema: str, question: str):
    """Generate a valid SQL query for the given schema and question.
    Schema: {schema}
    Question: {question}
    SQL:"""

# 或使用正则约束确保输出以 SELECT 开头
model = outlines.models.transformers("Qwen/Qwen2.5-72B-Instruct")
generator = outlines.generate.regex(model, r"SELECT\s+.+")
sql = generator(sql_prompt(schema, question))
```

**注意**：此方案需要本地部署模型，API 调用（如 DashScope）无法直接使用。可作为未来自建模型的优化方向。

### 3.10 速度优化汇总

| 优化项 | 预计节省 | 难度 | 优先级 |
|--------|---------|------|--------|
| Embedding 缓存 | 200ms | 低 | P0 |
| 术语表/Fallback Redis 缓存 | 500ms-1s | 低 | P0 |
| 权限计算去重 | 200-500ms | 低 | P0 |
| Rewriter/Schema 预取并行 | 1-3s | 中 | P1 |
| 超时预算自适应 | 间接（减少超时失败） | 中 | P1 |
| 大结果集分块 | 感知速度提升 | 中 | P2 |
| 数据源密码缓存 | 50-100ms | 低 | P2 |

---

## 四、优化方向二：提高 SQL 准确度

### 4.1 引入列级 Schema Linking（最关键优化）

**现状**：`schema_linker.py` 只做表级裁剪，且阈值为 3 张表（太保守）。所有列都进入 SQL 生成上下文。
**方案**：实现 RSL-SQL 的双向 Schema Linking 策略。

```
正向链接：NL 问题 → 匹配相关列（embedding 相似度 + 关键词匹配）
反向链接：SQL 模板草稿 → 提取需要的列
投票：正向 ∩ 反向 → 高置信列集合
```

**参考项目**：[RSL-SQL](https://github.com/Laqcce-cao/RSL-SQL)（94% 召回率，83% 列裁剪）

**实现建议**：

```python
# schema_linker.py - 增强版
class BidirectionalSchemaLinker:
    async def link(self, question: str, schema_context: list, llm) -> LinkedSchema:
        # 正向：embedding 相似度 + 关键词
        forward_cols = await self.forward_prune(question, schema_context)

        # 反向：生成 SQL 草稿，提取用到的列
        draft_sql = await self.generate_draft(question, schema_context, llm)
        backward_cols = self.extract_cols_from_sql(draft_sql)

        # 投票：交集为高置信，并集为候选
        high_conf = forward_cols & backward_cols
        candidates = forward_cols | backward_cols

        return LinkedSchema(
            tables=self.select_tables(candidates),
            columns=candidates,
            high_confidence=high_conf
        )
```

### 4.2 Schema Linking 阈值提高

**现状**：`> 3` 张表才触发。
**依据**：论文 "Death of Schema Linking"（arXiv:2408.07702）指出，对于 50 张表以内的 schema，现代 LLM 处理无关列的能力很强。
**方案**：阈值提高到 8-10 张表，仅在大 schema 时才做裁剪。

```python
# schema_linker.py
SCHEMA_LINKING_THRESHOLD = 8  # 从 3 改为 8

async def run_schema_linker(state):
    if len(schema_context) <= SCHEMA_LINKING_THRESHOLD:
        return state  # 跳过，让 LLM 自行处理
    # ... 执行列级裁剪
```

### 4.3 丰富列元数据描述

**现状**：列只有名称和类型。
**方案**：参考 WrenAI 的 MDL 语义层，为列添加业务描述、示例值、计算公式。

**参考项目**：[Canner/WrenAI](https://github.com/Canner/WrenAI) 的 Modeling Definition Language

```python
# 在 RAG 检索时，列上下文包含：
column_context = {
    "name": "order_amount",
    "type": "DECIMAL(10,2)",
    "description": "订单实付金额，单位元",           # 人工维护的描述
    "sample_values": ["199.00", "59.90", "1299.00"], # 采样值
    "glossary_term": "订单金额",                      # 术语表关联
    "business_formula": "unit_price * quantity - discount",  # 计算公式
    "confidence_score": 85,                           # 置信度
    "tags": ["PII:financial", "domain:order"],         # 标签
}
```

**参考论文**：PET-SQL（arXiv:2403.09732）的 Reference-Enhanced Representation，加入采样值后准确度提升 5-15%。

### 4.4 SQL-to-Schema 二次确认

**方案**：生成 SQL 后，反向提取实际使用的表/列，与 Schema Linking 结果交叉验证。

**参考论文**：MAG-SQL（arXiv:2408.07930）

```python
# 在 SQL_Validator 节点中增加
def validate_schema_usage(sql: str, linked_schema: LinkedSchema) -> ValidationResult:
    used_tables, used_cols = extract_schema_from_sql(sql)
    hallucinated_tables = used_tables - linked_schema.tables
    hallucinated_cols = used_cols - linked_schema.columns
    if hallucinated_tables or hallucinated_cols:
        return ValidationResult(
            valid=False,
            error=f"幻觉检测：使用了未链接的表 {hallucinated_tables} 或列 {hallucinated_cols}",
            suggestion="请仅使用已识别的表和列"
        )
```

### 4.5 多候选生成 + LLM-as-Judge（CHASE-SQL 模式）

**方案**：生成 2-3 个 SQL 候选，用不同策略（直接生成、分步推理、先分解再生成），然后 LLM-as-Judge 从语法正确性、Schema 正确性、语义对齐、执行一致性四个维度评分，选最优。

**参考项目**：[salesforce/CHASE-SQL](https://github.com/salesforce/CHASE-SQL)（Spider + BIRD SOTA）

```python
# 新增节点：candidate_judge
async def run_candidate_judge(state: AgentState) -> dict:
    """多候选 SQL 生成 + LLM-as-Judge 选择最优"""
    question = state["rewritten_question"]
    schema = state["schema_context"]

    # 三种策略生成候选
    candidates = await asyncio.gather(
        generate_sql_direct(question, schema),      # 直接生成
        generate_sql_cot(question, schema),          # Chain-of-Thought
        generate_sql_decompose(question, schema),    # 先分解再生成
    )

    # 执行每个候选
    results = []
    for sql in candidates:
        exec_result = await execute_sql_safe(sql)
        results.append({"sql": sql, "result": exec_result})

    # LLM-as-Judge 评分
    judge_prompt = f"""从以下 {len(candidates)} 个 SQL 候选中选择最佳方案。
    评分维度：语法正确性、Schema 正确性、语义对齐、执行一致性。

    用户问题：{question}
    Schema：{schema}
    候选 SQL 及执行结果：{results}

    输出格式：{{"best_index": 0, "reason": "...", "scores": [85, 90, 78]}}"""

    judgment = await call_llm(judge_prompt, response_format="json")
    best_sql = candidates[judgment["best_index"]]

    return {"generated_sql": best_sql, "candidate_scores": judgment["scores"]}
```

### 4.6 多 Agent 验证（MAC-SQL 模式）

**方案**：参考 MAC-SQL 的 Selector-Refiner 模式，增加独立验证 Agent。

**参考项目**：[BeaverAI/MAC-SQL](https://github.com/BeaverAI/MAC-SQL)

```
当前：Generator → Validator → Executor（串行）
优化：Generator → [Validator + SemanticVerifier 并行] → Executor

SemanticVerifier 检查：
1. SQL 语义是否匹配问题意图？
2. JOIN 条件是否正确？
3. WHERE 条件是否遗漏或多余？
4. 聚合函数是否正确？
```

### 4.6 执行反馈自校正（e-SQL 模式）

**现状**：执行失败后通过字符串分类错误，回退路径粗糙。
**方案**：参考 e-SQL 的 Execute-Then-Debug，将执行错误 + 结果反馈给 LLM 做针对性修正。

```python
# executor 节点增强
async def execute_with_feedback(state, llm):
    sql = state["sql"]
    result = await execute_sql(sql)

    if result.error:
        # 将错误信息 + 原 SQL + schema 上下文反馈给 LLM
        correction_prompt = f"""
        SQL 执行失败：
        错误信息：{result.error}
        原始 SQL：{sql}
        数据库 schema：{state['schema_context']}

        请修正 SQL。只输出修正后的 SQL，不要解释。
        """
        corrected_sql = await llm.invoke(correction_prompt)
        return await execute_sql(corrected_sql)

    # 结果合理性检查
    if result.row_count == 0 and not is_existence_question(state["question"]):
        # 返回 0 行但不是存在性问题，可能是条件过严
        relaxation_prompt = f"SQL 返回空结果，请检查 WHERE 条件是否过严：{sql}"
        # ...
```

### 4.8 自一致性投票（Self-Consistency Voting）

**方案**：生成 N 个 SQL 候选（temperature > 0），执行每个，比较结果集（非 SQL 字符串）。出现次数最多的结果集胜出。

**关键洞察**：语义等价的查询产生相同结果集，因此基于执行结果的投票比字符串匹配更可靠。

```python
async def self_consistency_vote(question: str, schema: str, n_candidates: int = 3):
    """自一致性投票：多候选执行结果投票"""
    candidates = await asyncio.gather(*[
        generate_sql(question, schema, temperature=0.3 + i * 0.2)
        for i in range(n_candidates)
    ])

    results = []
    for sql in candidates:
        exec_result = await execute_sql_safe(sql)
        if exec_result.success:
            # 结果集哈希用于比较
            result_hash = hash(frozenset(str(row) for row in exec_result.rows))
            results.append({"sql": sql, "hash": result_hash, "rows": exec_result.row_count})

    # 投票：相同结果集的候选归为一组
    from collections import Counter
    hash_counts = Counter(r["hash"] for r in results)
    most_common_hash = hash_counts.most_common(1)[0][0]

    # 返回得票最多的 SQL
    winner = next(r for r in results if r["hash"] == most_common_hash)
    confidence = hash_counts[most_common_hash] / len(results)

    return {"sql": winner["sql"], "confidence": confidence, "agreement": hash_counts[most_common_hash]}
```

### 4.9 Few-shot 选择升级

**现状**：字符重叠相似度（`fewshot.py:132-143`）。
**方案**：embedding 相似度 + 结构相似度混合评分。

**参考论文**：DAIL-SQL（arXiv:2312.17045）的动态自适应选择

```python
# fewshot.py
async def select_fewshot(question: str, candidates: list, top_k: int = 3):
    q_emb = await embed_single(question)

    scored = []
    for c in candidates:
        # 语义相似度（embedding）
        semantic_sim = cosine_similarity(q_emb, c.question_embedding)
        # 结构相似度（SQL 模板）
        structural_sim = sql_skeleton_similarity(c.sql)
        # 综合评分
        score = 0.6 * semantic_sim + 0.4 * structural_sim
        scored.append((score, c))

    scored.sort(reverse=True)
    return [c for _, c in scored[:top_k]]
```

### 4.8 准确度优化汇总

| 优化项 | 预计提升 | 难度 | 优先级 |
|--------|---------|------|--------|
| 列级 Schema Linking | **+10-15%** | 高 | P0 |
| 丰富列元数据描述 | +5-15% | 中 | P0 |
| Schema Linking 阈值提高 | +3-5%（减少误裁剪） | 低 | P0 |
| SQL-to-Schema 幻觉检测 | +3-5% | 中 | P1 |
| 执行反馈自校正 | +5-8% | 中 | P1 |
| Few-shot embedding 选择 | +3-5% | 中 | P1 |
| 多 Agent 验证 | +5-10% | 高 | P2 |

---

## 五、优化方向三：字段置信度自提升机制重构

### 5.1 当前机制评估

当前置信度机制（`ConfidenceCalculatorImpl.java`）：

```
成功查询 +2, 点赞 +10, 确认踩 -15, 群体阈值 -5
分数范围：0-100，等级：HIGH(80+), MEDIUM(50-79), LOW(25-49), VERY_LOW(0-24)
```

**问题**：
- ❌ 固定 delta 累加，不考虑频率（100 次成功查询 vs 1 次成功查询权重一样）
- ❌ 无时间衰减（3 年前的高分 vs 昨天的高分一样）
- ❌ 无批量聚合（100 次成功 = 100 次数据库写入）
- ❌ 不考虑查询复杂度（简单查询成功 vs 复杂多表查询成功权重一样）

### 5.2 参考方案：加权衰减模型

**参考**：OpenMetadata 的质量评分体系 + 学术论文中的置信度校准方法

```java
/**
 * 改进的置信度计算模型
 *
 * 核心思路：
 * 1. 事件加权：不同事件类型有不同基础权重
 * 2. 时间衰减：近期事件权重更高
 * 3. 频率聚合：批量处理高频事件
 * 4. 查询复杂度加权：复杂查询的成功更有价值
 */
public class ImprovedConfidenceCalculator {

    // 事件基础权重
    private static final Map<ConfidenceEvent, Double> BASE_WEIGHTS = Map.of(
        QUERY_SUCCESS,       1.0,    // 查询成功
        QUERY_FAIL,         -2.0,    // 查询失败
        USER_LIKE,           5.0,    // 用户点赞
        USER_DISLIKE,       -8.0,    // 用户踩
        GROUP_THRESHOLD,    -3.0,    // 群体阈值触发
        GOVERNANCE_ISSUE,   -5.0,    // 治理问题
        QUALITY_CHECK_PASS, 2.0,    // 质量检查通过
        QUALITY_CHECK_FAIL, -4.0    // 质量检查失败
    );

    // 时间衰减因子（半衰期 30 天）
    private static final double HALF_LIFE_DAYS = 30.0;

    /**
     * 计算衰减后的分数
     * 使用指数衰减：weight *= exp(-ln2 * days_ago / HALF_LIFE)
     */
    public double calculateWithDecay(List<ConfidenceEvent> events) {
        double score = BASE_SCORE; // 初始分数 50
        double now = System.currentTimeMillis();

        for (ConfidenceEvent event : events) {
            double daysAgo = (now - event.getTimestamp()) / (1000.0 * 86400);
            double decayFactor = Math.exp(-Math.log(2) * daysAgo / HALF_LIFE_DAYS);
            double weight = BASE_WEIGHTS.get(event.getType()) * decayFactor;

            // 查询复杂度加权
            if (event.getQueryComplexity() != null) {
                weight *= (1 + event.getQueryComplexity() * 0.1); // 复杂度 1-5，加权 10%-50%
            }

            score += weight;
        }

        return Math.max(0, Math.min(100, score));
    }

    /**
     * 批量聚合模式
     * 将 N 个同类事件聚合为一个加权事件，减少 DB 写入
     */
    public ConfidenceEvent aggregateEvents(List<ConfidenceEvent> events) {
        Map<ConfidenceEventType, List<ConfidenceEvent>> grouped =
            events.stream().collect(Collectors.groupingBy(ConfidenceEvent::getType));

        // 每个类型取平均时间戳和加权总分
        // 100 次 QUERY_SUCCESS → 1 个聚合事件，权重 = 100 * 1.0 * 时间衰减
    }
}
```

### 5.3 置信度反馈闭环增强

**现状**：置信度仅在 SQL 生成阶段通过 reranker 加分（+0.1）。
**方案**：置信度应贯穿整个链路。

```
查询前：置信度影响 Schema Linking（高置信列优先）
查询中：置信度影响 SQL 生成（高置信列表达式优先）
查询后：查询结果反馈更新置信度
治理端：治理 issue 直接影响置信度
```

**参考**：DataHub 的 Assertions 框架，质量断言结果直接影响数据资产评分。

### 5.4 置信度可视化增强

**方案**：在查询结果的「信任度」标签页中，展示更详细的置信度分解。

```
列名：order_amount
当前置信度：78/100 (MEDIUM)

置信度分解：
├── 查询成功率：+24（近 30 天 12 次成功查询）
├── 用户反馈：+15（2 次点赞，0 次踩）
├── 治理状态：-5（1 个低优先级 issue）
├── 质量检查：+8（空值率 2%，唯一性通过）
└── 时间衰减：-4（部分事件超过 30 天）

趋势：📈 近 7 天上升 6 分
```

### 5.5 这样设置 OK 吗？—— 对当前机制的评估

**当前机制的合理性**：
- ✅ 简单可解释，用户容易理解
- ✅ 事件驱动，实时性好
- ✅ 分数范围 0-100 直观

**需要改进的地方**：
- ⚠️ 固定 delta 没有区分"重要查询成功"和"简单查询成功"
- ⚠️ 无衰减导致"一劳永逸"——一个字段可能因为历史高分永远保持 HIGH，即使最近查询频繁失败
- ⚠️ 高频事件产生大量 DB 写入，应做批量聚合
- ⚠️ 置信度没有充分利用——只在 reranker 中加 0.1 分，应在 Schema Linking 阶段就发挥作用

**结论**：基本框架 OK，但需要**加入时间衰减、事件加权、批量聚合、链路渗透**四个增强。

---

## 六、优化方向四：元数据治理增强

### 6.1 借鉴 OpenMetadata 的实体图模型

**现状**：DataOcean 已有 `metadata_entity` 和 `metadata_relationship` 表。
**增强方向**：

1. **列级血缘**：参考 Apache Atlas 的列级血缘实现，追踪列的上下游关系
2. **分类传播**：标记父表为 PII，自动传播到子表/视图
3. **质量评分聚合**：参考 OpenMetadata，列质量检查结果自动聚合为表/数据源评分

**参考**：[OpenMetadata](https://github.com/open-metadata/OpenMetadata) 的实体模型

### 6.2 元数据驱动的 Schema Linking

**方案**：将治理元数据（描述、标签、术语、质量分数）注入 Schema Linking 上下文。

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

**参考**：WrenAI 的 MDL 语义层，DataHub 的 Aspect 模型。

### 6.3 自动标签增强

**现状**：Python `AutoTagger` 基于列名模式匹配。
**增强**：

1. **基于采样值推断**：检测列值中的 PII（手机号、邮箱、身份证）
2. **基于查询模式推断**：被 WHERE 条件高频使用的列可能是维度列
3. **基于 JOIN 模式推断**：频繁 JOIN 的列可能是外键

**参考**：OpenMetadata 的 Auto-Classification，DataHub 的 Plugin 自动标签。

### 6.4 GraphRAG 用于 JOIN 路径发现

**现状**：JOIN 路径通过 skills.md 人工维护。
**方案**：利用已有的 `metadata_entity` 和 `metadata_relationship` 表，通过图遍历自动发现 JOIN 路径。

**参考项目**：[microsoft/graphrag](https://github.com/microsoft/graphrag)

```python
# rag/graph_retriever.py - 图检索器
class GraphJoinPathFinder:
    """基于实体关系图的 JOIN 路径发现"""

    async def find_join_paths(self, tables: list[str], metadata_graph) -> list[JoinPath]:
        """从元数据关系图中找到连接指定表的所有可能路径"""
        paths = []
        for i, t1 in enumerate(tables):
            for t2 in tables[i+1:]:
                # BFS 找最短 JOIN 路径
                shortest = self.bfs_join_path(metadata_graph, t1, t2)
                if shortest:
                    paths.append(shortest)

        # 按路径长度排序（越短越可靠）
        paths.sort(key=lambda p: len(p.edges))
        return paths[:3]  # 最多返回 3 条路径

    def bfs_join_path(self, graph, source, target):
        """BFS 搜索最短 JOIN 路径"""
        from collections import deque
        queue = deque([(source, [source])])
        visited = {source}

        while queue:
            node, path = queue.popleft()
            if node == target:
                return JoinPath(tables=path, edges=self.get_join_edges(graph, path))

            for neighbor in graph.get_neighbors(node, rel_type="FOREIGN_KEY"):
                if neighbor not in visited:
                    visited.add(neighbor)
                    queue.append((neighbor, path + [neighbor]))
        return None
```

**与 skills.md 的关系**：GraphRAG 发现的 JOIN 路径可以作为 skills.md 的自动补充，人工审核后发布。

### 6.5 治理 Issue 与置信度联动

**现状**：治理 issue 和置信度是独立系统。
**方案**：治理 issue 状态变化自动触发置信度调整。

```java
// GovernanceIssueService - issue 状态变化时
@EventListener
public void onIssueStatusChanged(IssueStatusChangedEvent event) {
    if (event.getNewStatus() == IssueStatus.CONFIRMED) {
        confidenceCalculator.adjust(event.getColumnId(), ConfidenceEvent.GOVERNANCE_ISSUE, -5);
    } else if (event.getNewStatus() == IssueStatus.RESOLVED) {
        confidenceCalculator.adjust(event.getColumnId(), ConfidenceEvent.QUALITY_CHECK_PASS, +3);
    }
}
```

---

## 七、优化方向五：Agent 架构升级

### 7.1 当前 vs 目标架构

**当前（线性管道）**：
```
START → Rewriter → Retriever → Linker → Generator → Validator → Executor → Visualizer → END
```

**目标（并行 + 条件路由 + 自校正）**：
```
START → [Rewriter ‖ Prefetch] → Retriever → Linker(阈值≥8才触发) → Generator
    → [Validator ‖ SemanticCheck 并行]
    → Executor
    → (失败？→ FeedbackCorrection → 重新 Executor，最多 2 次)
    → Visualizer → END
```

### 7.2 LangGraph 并行节点实现

**参考**：DB-GPT 的 AWEL 工作流引擎

```python
# graph.py - 增强版图结构
from langgraph.graph import StateGraph, START, END

def build_enhanced_graph() -> StateGraph:
    graph = StateGraph(AgentState)

    # 并行节点
    graph.add_node("query_rewriter", run_rewriter)
    graph.add_node("metadata_prefetch", run_metadata_prefetch)

    # 串行节点
    graph.add_node("schema_retriever", run_retriever)
    graph.add_node("schema_linker", run_linker)
    graph.add_node("sql_generator", run_generator)
    graph.add_node("sql_validator", run_validator)
    graph.add_node("sql_executor", run_executor)
    graph.add_node("feedback_correction", run_feedback_correction)
    graph.add_node("visualizer", run_visualizer)

    # 并行启动
    graph.add_edge(START, "query_rewriter")
    graph.add_edge(START, "metadata_prefetch")
    graph.add_edge(["query_rewriter", "metadata_prefetch"], "schema_retriever")

    # 条件路由：schema 大小决定是否做 Schema Linking
    graph.add_conditional_edges("schema_retriever", should_link_schema, {
        "link": "schema_linker",
        "skip": "sql_generator"
    })

    graph.add_edge("schema_linker", "sql_generator")

    # Validator + SemanticCheck 并行（可选）
    graph.add_edge("sql_generator", "sql_validator")

    # 条件路由：执行结果
    graph.add_conditional_edges("sql_executor", route_after_execution, {
        "success": "visualizer",
        "retry": "feedback_correction",
        "fail": END
    })

    graph.add_edge("feedback_correction", "sql_executor")
    graph.add_edge("visualizer", END)

    return graph.compile()
```

### 7.3 自校正循环（参考 CHESS / e-SQL）

```python
# feedback_correction 节点
async def run_feedback_correction(state: AgentState) -> dict:
    """执行反馈自校正：将错误信息反馈给 LLM 修正 SQL"""
    error = state["execution_error"]
    sql = state["generated_sql"]
    schema = state["schema_context"]

    correction_prompt = CORRECTION_TEMPLATE.format(
        error=error, sql=sql, schema=schema,
        question=state["rewritten_question"]
    )

    corrected_sql = await call_llm(correction_prompt, temperature=0.1)

    return {
        "generated_sql": corrected_sql,
        "retry_count": state.get("retry_count", 0) + 1
    }

# 路由函数
def route_after_execution(state: AgentState) -> str:
    if state.get("execution_error"):
        if state.get("retry_count", 0) >= 2:
            return "fail"  # 最多重试 2 次
        return "retry"
    return "success"
```

---

## 八、实施优先级与路线图

### Phase 1：低悬果实（1-2 周）

| # | 优化项 | 影响 | 文件 |
|---|--------|------|------|
| 1 | Embedding 缓存 | 速度 -200ms | `rag/service.py` |
| 2 | 术语表 Redis 缓存 | 速度 -500ms | `PythonAgentClientImpl.java` |
| 3 | Fallback Chunks Redis 缓存 | 速度 -200ms | `PythonAgentClientImpl.java` |
| 4 | 权限计算请求级缓存 | 速度 -300ms | `QueryTaskServiceImpl.java` |
| 5 | Schema Linking 阈值提高到 8 | 准确度 +3-5% | `schema_linker.py` |
| 6 | 置信度加入时间衰减 | 更合理的评分 | `ConfidenceCalculatorImpl.java` |

### Phase 2：核心优化（2-4 周）

| # | 优化项 | 影响 | 文件 |
|---|--------|------|------|
| 7 | 列级 Schema Linking | 准确度 +10-15% | `schema_linker.py` |
| 8 | 列元数据描述丰富化 | 准确度 +5-15% | `rag/retriever.py`, Java metadata |
| 9 | Few-shot embedding 选择 | 准确度 +3-5% | `rag/fewshot.py` |
| 10 | 执行反馈自校正循环 | 准确度 +5-8% | `agent/graph.py`, `executor.py` |
| 11 | 置信度批量聚合 | 减少 DB 写入 | `ConfidenceCalculatorImpl.java` |
| 12 | 治理-置信度联动 | 更准确的评分 | Java event listener |

### Phase 3：架构升级（4-6 周）

| # | 优化项 | 影响 | 文件 |
|---|--------|------|------|
| 13 | Agent 图并行化 | 速度 -1-3s | `agent/graph.py` |
| 14 | SQL-to-Schema 幻觉检测 | 准确度 +3-5% | `sandbox/validator.py` |
| 15 | 超时预算自适应 | 稳定性 | `infra/timeout_budget.py` |
| 16 | 置信度链路渗透 | 更好的 Schema Linking | 多文件 |
| 17 | 列级血缘 | 治理增强 | Java metadata |
| 18 | GraphRAG JOIN 路径发现 | 准确度 +3-5% | `rag/graph_retriever.py` |
| 19 | 语义查询缓存（GPTCache） | 速度 -2-5s（命中时） | `rag/service.py` |

### Phase 4：高级优化（6+ 周）

| # | 优化项 | 影响 |
|---|--------|------|
| 20 | 多候选 + LLM-as-Judge（CHASE-SQL） | 准确度 +5-10% |
| 21 | 自一致性投票 | 准确度 +3-5%，置信度校准 |
| 22 | 语义层 MDL（参考 WrenAI） | 准确度显著提升 |
| 23 | 自动标签增强（采样值推断） | 治理自动化 |
| 24 | 大结果集流式传输 | 用户体验 |
| 25 | 语法约束解码（Outlines，需自建模型） | 从源头防止格式错误 |

---

## 九、参考项目与论文索引

### 开源项目

| 项目 | 地址 | 借鉴点 |
|------|------|--------|
| WrenAI | https://github.com/Canner/WrenAI | MDL 语义层设计 |
| DB-GPT | https://github.com/eosphoros-ai/DB-GPT | Multi-Agent 工作流 |
| MAC-SQL | https://github.com/BeaverAI/MAC-SQL | Selector Agent 列级评分 |
| RSL-SQL | https://github.com/Laqcce-cao/RSL-SQL | 双向 Schema Linking |
| MAG-SQL | https://github.com/LancelotXWX/MAG-SQL | SQL-to-Schema 反向验证 |
| OpenMetadata | https://github.com/open-metadata/OpenMetadata | 实体图 + 质量评分 |
| DataHub | https://github.com/datahub-project/datahub | Aspect 模型 + 断言框架 |
| Apache Atlas | https://github.com/apache/atlas | 列级血缘 + 分类传播 |
| Vanna | https://github.com/vanna-ai/vanna | RAG Pipeline 简洁设计 |
| CHASE-SQL | https://github.com/salesforce/CHASE-SQL | 多候选 + LLM-as-Judge |
| GPTCache | https://github.com/zilliztech/GPTCache | 语义查询缓存 |
| GraphRAG | https://github.com/microsoft/graphrag | 图结构层次化检索 |
| Outlines | https://github.com/dottxt-ai/outlines | 语法约束解码 |
| sqlglot | https://github.com/tobymao/sqlglot | SQL AST 验证（已使用） |

### 学术论文

| 论文 | arXiv | 核心贡献 |
|------|-------|---------|
| RSL-SQL | 2411.00073 | 双向 Schema Linking，94% 召回 |
| CHESS | 2405.16755 | 多 Agent Schema 剪枝 + 单元测试 |
| MAC-SQL | 2312.11242 | Multi-Agent 协作，Selector/Refiner |
| MAG-SQL | 2408.07930 | Soft Schema Linking + SQL-to-Schema |
| PET-SQL | 2403.09732 | Reference-Enhanced，SOTA 87.6% |
| DIN-SQL | 2304.11015 | 分解式 ICL + Schema Linking |
| DAIL-SQL | 2312.17045 | 动态 Few-shot 选择 |
| C3 | 2307.07306 | Clear Prompting + 校准 |
| SQLfuse | 2407.14568 | Schema Mining（蚂蚁集团） |
| TA-SQL | 2405.15307 | Task Alignment 幻觉缓解 |
| Death of Schema Linking | 2408.07702 | 小 Schema 跳过 Linking |
| RESDSQL | 2302.05965 | Ranking-Enhanced 编码 |
| CHASE-SQL | 2410.01711 | 多候选生成 + LLM-as-Judge 排名 |
| Solid-SQL | 2412.12522 | 鲁棒 Schema Linking |
| Reflexion | 2303.11366 | 言语强化学习，跨尝试累积调试经验 |

---

> **文档维护**：本文档基于 2026-07-22 的调研结果编写，随着项目演进和新论文/开源项目出现，应定期更新。
