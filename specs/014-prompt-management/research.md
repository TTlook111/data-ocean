# Research: Prompt 管理模块

## Token 计算方案

**Decision**: 使用 tiktoken 库的 cl100k_base 编码器

**Rationale**: Qwen 系列模型的 tokenizer 与 OpenAI cl100k_base 高度兼容，tiktoken 是 Python 生态中最快的 tokenizer 实现（Rust 底层），计算 4000 token 耗时 < 1ms。

**Alternatives considered**:
- transformers AutoTokenizer: 需要下载模型文件，启动慢，内存占用大
- 自定义字符估算（1 中文字 ≈ 2 token）: 不精确，容易超出或浪费预算
- Qwen 官方 tokenizer: 需要额外依赖，且与 tiktoken 结果差异 < 5%

## 模板渲染引擎

**Decision**: Jinja2 模板引擎，使用 `{{variable}}` 语法

**Rationale**: Jinja2 是 Python 最成熟的模板引擎，支持条件判断、循环、过滤器，且 `{{}}` 语法与需求中的变量占位符一致。

**Alternatives considered**:
- Python str.format(): 功能太弱，不支持条件逻辑
- Mako: 功能强但语法复杂，学习成本高
- 自定义正则替换: 不支持复杂逻辑，维护成本高

## Token 预算裁剪策略

**Decision**: 优先级队列 + 从低优先级尾部截断

**Rationale**: 各变量按优先级分配预算上限，渲染时先填充高优先级内容，剩余空间分配给低优先级。超出时从最低优先级的尾部开始截断（保留开头更重要的信息）。

**Priority allocation** (total 4000):
1. schema: 1500 (表结构是 SQL 生成的基础)
2. skills_md: 1000 (业务语义说明)
3. few_shot_templates: 800 (示例 SQL)
4. conversationHistory: 500 (上下文连续性)
5. field_confidence: 200 (可信度辅助信息)

**Truncation rule**: 实际内容超出分配预算时，保留前 N 个 token，末尾追加 `[...truncated]` 标记。

## Python 获取模板方式

**Decision**: Python 通过 HTTP 调用 Java 内部 API 获取模板

**Rationale**: 保持 Java 为 Prompt 数据的唯一 source of truth，避免 Python 直连管理库带来的双写风险。Java 内部 API 响应时间 < 10ms，可接受。

**Alternatives considered**:
- Python 直连 MySQL 管理库: 破坏三层分离原则，双写风险
- Redis 缓存同步: 增加复杂度，MVP 阶段不需要
- 启动时全量加载 + 定时刷新: 版本切换有延迟，不满足"5 秒内生效"要求

## 版本回滚方案

**Decision**: 直接切换 active 标记，不复制内容

**Rationale**: 回滚 = 将目标版本的 is_active 设为 true，当前版本设为 false。简单高效，历史版本内容不可变，审计清晰。

**Alternatives considered**:
- 复制旧版本内容创建新版本: 版本号膨胀，语义不清晰
- 软删除当前版本: 丢失历史记录
