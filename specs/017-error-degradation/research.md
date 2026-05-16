# Research: 错误处理与降级模块

## 健康检查方案

**Decision**: Java 定时任务每 30 秒调用 Python /health，连续 3 次失败标记不可用

**Rationale**: 简单可靠，不引入额外依赖。Spring Boot 的 @Scheduled 即可实现。AtomicReference 保存状态，线程安全。

**Alternatives considered**:
- Spring Boot Actuator + Spring Cloud Circuit Breaker: 过重，MVP 不需要
- Sentinel: 功能强大但学习成本高，单实例场景杀鸡用牛刀
- 被动检测（请求失败时标记）: 第一个请求的用户体验差，主动检查更好

## LLM 重试策略

**Decision**: 仅对 timeout 和 rate-limit 重试 1 次，其他错误直接失败

**Rationale**: LLM API 的 timeout 和 rate-limit 是暂时性错误，重试有意义。其他错误（如 invalid request、auth error）重试无意义。限制 1 次重试避免雪崩。

**Retry conditions**:
- HTTP 408 (Request Timeout): 重试
- HTTP 429 (Too Many Requests): 等待 Retry-After header 指定时间后重试
- HTTP 5xx (Server Error): 重试
- HTTP 4xx (其他): 不重试，直接返回错误

**Backoff**: 固定 2 秒延迟（不用指数退避，只重试 1 次）

## CancellationToken 方案

**Decision**: Python 进程内 AtomicBoolean flag + 字典管理

**Rationale**: MVP 单实例部署，无需跨进程协调。用 threading.Event 或 asyncio.Event 实现，LangGraph 各节点执行前检查 flag。

**Implementation**:
```python
class CancellationToken:
    def __init__(self, task_id: str):
        self.task_id = task_id
        self._cancelled = asyncio.Event()

    def cancel(self):
        self._cancelled.set()

    def is_cancelled(self) -> bool:
        return self._cancelled.is_set()
```

**Lifecycle**:
1. 查询开始 → 创建 CancellationToken，存入 dict[task_id → token]
2. 取消请求 → 通过 task_id 找到 token，调用 cancel()
3. 各节点执行前 → 检查 is_cancelled()，为 True 则抛出 CancelledException
4. 查询结束 → 从 dict 中移除 token

**Alternatives considered**:
- Redis pub/sub: 跨进程方案，MVP 单实例不需要
- 数据库轮询: 延迟高，不适合实时取消
- 线程中断: Python asyncio 不支持线程中断语义

## Milvus 降级方案

**Decision**: 连接失败时使用 skills.md 中标记的前 5 张核心表 DDL 作为上下文

**Rationale**: skills.md 中的核心表是人工审核过的高质量元数据，即使没有向量检索，也能覆盖大部分常见查询。5 张表的 DDL 约 1000-1500 token，在预算范围内。

**Fallback flow**:
1. Milvus 连接失败 → 捕获异常
2. 从 skills.md 配置中读取 core_tables 列表（前 5 张）
3. 从管理库获取这些表的 DDL
4. 将 DDL 作为 schema 上下文传给 SQL 生成
5. 响应中标注 `degraded: true, degradation_note: "向量检索不可用，使用核心表上下文"`

**Alternatives considered**:
- 全量表 DDL: token 超出预算，且大部分表不相关
- 缓存最近 N 次查询的召回结果: 实现复杂，且缓存可能过期
- 直接报错: 用户体验差，核心表能覆盖 60%+ 的常见查询

## 超时预算分配

**Decision**: Python 总预算 100s，按节点分配

**Rationale**: Java 设置 120s 超时，Python 必须在 100s 内完成（留 20s 网络开销）。各节点按实际耗时特征分配预算。

**Budget allocation**:
| Node | Budget | Rationale |
|------|--------|-----------|
| Schema RAG (Milvus) | 10s | 向量检索通常 < 2s，留余量 |
| SQL Generation (LLM) | 40s | LLM 生成最耗时，含 1 次重试 |
| SQL Validation (AST) | 5s | 纯计算，极快 |
| SQL Execution (DB) | 30s | 复杂查询可能慢 |
| Chart Generation (LLM) | 15s | 第二次 LLM 调用 |

**Enforcement**: 各节点开始前计算剩余预算，如果剩余 < 节点最低需求，提前终止并返回已有结果。

## SSE 断开检测

**Decision**: Java 检测 SSE 连接关闭事件，主动通知 Python 取消

**Rationale**: 用户关闭浏览器或切换页面时，SSE 连接断开。Java 通过 SseEmitter 的 onCompletion/onTimeout/onError 回调检测，然后调用 Python 取消 API。

**Flow**:
1. SseEmitter.onCompletion() → 正常结束，不处理
2. SseEmitter.onTimeout() → 超时，通知 Python 取消
3. SseEmitter.onError() → 连接异常断开，通知 Python 取消

## 错误消息映射

**Decision**: 所有技术错误映射为用户友好中文，技术细节只记录日志

**Error message mapping**:
| 技术错误 | 用户提示 |
|----------|----------|
| LLM timeout (2次) | "AI 服务繁忙，请稍后再试" |
| LLM invalid response | "AI 理解出现偏差，请换个方式提问" |
| Milvus unavailable | "知识检索服务暂时不可用，已使用备用方案" |
| SQL execution timeout | "查询超时，请缩小查询范围或添加时间条件" |
| SQL validation failed | "生成的查询不符合安全规范，请换个方式提问" |
| Python service down | "AI 服务暂时不可用，请稍后再试" |
| DB connection exhausted | "系统繁忙，请稍后再试" |
| User cancelled | "查询已取消" |
| Permission denied | "您没有权限访问该数据" |
