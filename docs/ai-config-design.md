# AI 服务配置管理 — 设计文档

> 版本：v1.2 | 更新日期：2026-06-03

## 一、背景与目标

### 1.1 现状问题

当前 AI 服务配置（API Key、模型名、Base URL 等）存放在 Python 端的 `.env` 文件中，修改需要手动编辑文件并重启服务。存在以下问题：

- **无法在线切换模型**：改模型必须改 `.env` + 重启 Python 服务
- **不支持多供应商**：只能配置一个 DashScope 供应商，无法切换到 OpenAI、DeepSeek 等
- **Chat 和 Embedding 混在一起**：`QWEN_MODEL` 和 `QWEN_EMBEDDING_MODEL` 共用同一个 API Key，但实际上最优组合可能是跨供应商的（Chat 用通义千问，Embedding 用 OpenAI）
- **Embedding 模型切换无感知**：切换 Embedding 模型会导致 Milvus 向量维度不兼容，但当前没有任何警告或自动处理

### 1.2 设计目标

1. 支持**多 AI 供应商**管理，每个供应商独立配置 API Key + Base URL
2. **Chat 模型和 Embedding 模型分开选择**，可跨供应商混搭
3. 切换 Embedding 模型时**自动检测维度变化并警告**，确认后触发全量重新向量化
4. 所有配置**在线热更新**，无需重启任何服务
5. API Key **AES-256-GCM 加密存储**，前端只显示掩码

### 1.3 最终设计结论

本功能采用 **active/pending 双配置 + pending 索引构建 + 成功发布后延迟清理** 的方案：

1. Chat 模型切换可直接热重载为 active，后续查询立即生效。
2. Embedding 模型切换时，新配置先进入 `ai.pending.embedding`，不覆盖 `ai.active.embedding`。
3. pending 索引用 pending Embedding 独立构建；重建期间查询侧继续使用 active Embedding 和旧 active 索引。
4. pending 索引完整写入并校验成功后，系统再将 pending 发布为 active，并回调 Python 热重载查询侧配置。
5. 新 active 索引真正生效后，才清理上一版旧 active 索引；pending 构建失败时仅清理 pending 残留，旧索引继续可用。
6. 维度变化时必须创建新的 Milvus collection；同一个 collection 只允许存储同一维度向量。

---

## 二、配置属性分析

### 2.1 当前配置项

| 属性 | 当前位置 | 说明 |
|------|---------|------|
| `DASHSCOPE_API_KEY` | .env | DashScope API 密钥 |
| `DASHSCOPE_BASE_URL` | .env | DashScope API 端点 |
| `QWEN_MODEL` | .env | Chat 模型名 |
| `QWEN_EMBEDDING_MODEL` | .env | Embedding 模型名 |
| `EMBEDDING_DIMENSION` | .env | 向量维度 |
| `LLM_TIMEOUT` | .env | LLM 调用超时（秒） |
| `LLM_MAX_RETRIES` | .env | 最大重试次数 |
| `LLM_TEMPERATURE` | .env | 生成温度（0-2） |

### 2.2 改造后归属

| 属性 | 归属层级 | 是否 UI 可配 | 说明 |
|------|---------|:---:|------|
| API Key | 供应商级 | ✅ | 每个供应商一个，AES-256-GCM 加密存储 |
| Base URL | 供应商级 | ✅ | 每个供应商一个 |
| Chat 模型名 | Chat 配置 | ✅ | 从供应商的 Chat 模型列表中选择 |
| Embedding 模型名 | Embedding 配置 | ✅ | 从供应商的 Embedding 模型列表中选择 |
| Embedding 维度 | Embedding 配置 | ⚠️ 自动检测 + 可手动覆盖 | 优先从模型 API metadata 获取，fallback 用测试文本推断 |
| 超时时间 | Chat 配置 | ✅ | 默认 120 秒 |
| 最大重试 | Chat 配置 | ⚠️ 高级选项 | 默认 2 次 |
| 温度 | Chat 配置 | ✅ | 默认 0.3 |

---

## 三、数据模型

### 3.1 供应商模型

```json
{
  "id": "dashscope",
  "name": "通义千问",
  "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "apiKey": "sk-***encrypted***",
  "chatModels": [
    {
      "name": "qwen-plus",
      "displayName": "Qwen Plus",
      "maxContext": 131072
    },
    {
      "name": "qwen3.7-max",
      "displayName": "Qwen 3.7 Max",
      "maxContext": 131072
    }
  ],
  "embeddingModels": [
    {
      "name": "text-embedding-v4",
      "displayName": "Text Embedding V4",
      "dimension": 1024
    }
  ],
  "status": "connected",
  "lastTestedAt": "2026-06-02T10:00:00"
}
```

### 3.2 当前生效配置模型

```json
{
  "chat": {
    "providerId": "dashscope",
    "model": "qwen-plus",
    "temperature": 0.3,
    "timeout": 120,
    "maxRetries": 2
  },
  "embedding": {
    "providerId": "dashscope",
    "model": "text-embedding-v4",
    "dimension": 1024
  }
}
```

### 3.3 数据库存储（sys_config 表）

每个供应商独立存储为一行，避免并发编辑时整体 JSON 覆盖冲突：

| config_key | config_value | 说明 |
|-----------|-------------|------|
| `ai.provider.{id}` | JSON 对象（apiKey 加密） | 单个供应商配置，如 `ai.provider.dashscope` |
| `ai.active.chat` | JSON 对象 | 当前 Chat 模型配置 |
| `ai.active.embedding` | JSON 对象 | 当前线上生效的 Embedding 配置（查询侧只使用 active） |
| `ai.pending.embedding` | JSON 对象 | 待切换的 Embedding 配置（重建完成前不影响线上查询） |
| `ai.vectorize.status` | JSON 对象 | 向量化状态标记（见 4.5 节，包含 active/pending 索引信息） |

> **迁移说明**：旧的 `ai.providers`（单行 JSON 数组）在首次启动时自动拆分为多行 `ai.provider.{id}`，迁移完成后删除旧 key。

---

## 四、核心交互流程

### 4.1 添加供应商

```
管理员点击"添加供应商"
  ↓
填写：供应商名称、API Key、Base URL
  ↓
点击"测试连接"
  ↓
后端调用供应商 GET /v1/models 接口
  ↓
自动拉取可用模型列表，分为 chatModels 和 embeddingModels
  ↓
保存供应商配置（API Key AES-256-GCM 加密入库）
```

### 4.2 切换 Chat 模型

```
管理员在"模型选择"区域选择新的 Chat 模型
  ↓
调整温度/超时参数
  ↓
点击"保存"
  ↓
Java 更新 sys_config → 回调 Python POST /internal/config/reload
  ↓
Python 清除 ChatOpenAI 缓存 → 用新模型重建实例
  ↓
后续查询自动使用新模型（即时生效）
```

**热重载失败处理**：如果 Python 回调失败（服务不可用），Java 端将 `ai.active.chat` 的 `reloadPending` 标记为 `true`。Python 服务重启后通过健康检查接口主动拉取待重载配置，确保最终一致。

### 4.3 切换 Embedding 模型（关键流程）

```
管理员在"模型选择"区域选择新的 Embedding 模型
  ↓
前端检测到 Embedding 模型发生变化
  ↓
弹出警告对话框：
  ┌─────────────────────────────────────────┐
  │ ⚠️ 切换 Embedding 模型                    │
  │                                         │
  │ 当前模型: text-embedding-v4 (1024 维)    │
  │ 新模型: text-embedding-3-small (1536 维) │
  │                                         │
  │ 切换后需要重新向量化所有已发布知识库。     │
  │ 新索引发布成功前，旧索引会继续保留并服务查询。│
  │ 新索引真正生效后，系统再清理旧索引。       │
  │                                         │
  │ [取消]  [确认切换并重新向量化]             │
  └─────────────────────────────────────────┘
  ↓ (确认)
Java 将新 Embedding 写入 ai.pending.embedding → 将向量化状态标记为 REINDEXING
  ↓
Java 触发全量重新向量化（pending 索引构建，见 4.6 节）
  ↓
前端展示向量化进度（轮询 GET /internal/rag/re-vectorize/progress）
  ↓
Python 使用 pending Embedding 生成新索引；查询侧仍使用 ai.active.embedding + 旧索引
  ↓
新索引全部写入并校验成功
  ↓
Java 将 ai.pending.embedding 发布为 ai.active.embedding → 回调 Python POST /internal/config/reload
  ↓
Python 清除 Embeddings 缓存 → 查询侧切换为新 active 配置
  ↓
清理旧索引，状态切回 NORMAL
```

**重建期间查询处理**：向量化状态为 `REINDEXING` 期间，查询侧不使用 pending Embedding，继续使用 `ai.active.embedding` 和旧索引，因此不会发生 query embedding 与旧索引维度不匹配。前端展示提示信息："知识库索引正在重建，当前查询仍使用上一版索引，新配置将在重建完成后生效"。

### 4.4 维度自动检测

```
管理员选择新的 Embedding 模型
  ↓
前端调用后端接口：POST /api/admin/system/ai-config/detect-dimension
  ↓
后端调用 Python /internal/rag/detect-dimension
  ↓
Python 尝试从模型 API metadata 获取维度（GET /v1/models 响应的 dimensions 字段）
  ↓ (API 不返回维度时)
Python 用新模型对测试文本 "DataOcean dimension detection test" 做 embedding
  ↓
返回向量长度 = 维度
  ↓
前端自动填入维度字段（可手动覆盖修正）
```

### 4.5 向量化状态管理

引入 `ai.vectorize.status` 配置项，记录当前向量化的全局状态：

```json
{
  "status": "NORMAL",
  "active": {
    "providerId": "dashscope",
    "model": "text-embedding-v4",
    "dimension": 1024,
    "indexVersion": "v2",
    "collection": "dataocean_schema_1024"
  },
  "pending": null,
  "totalChunks": 500,
  "completedChunks": 0,
  "failedChunks": 0,
  "startedAt": null,
  "completedAt": null
}
```

| 状态 | 含义 | RAG 检索行为 |
|------|------|-------------|
| `NORMAL` | 正常 | 正常向量检索 |
| `REINDEXING` | pending 索引重建中 | 继续使用 active Embedding + active 索引 |
| `REINDEX_FAILED` | pending 索引重建失败 | 继续使用 active 索引 + 告警通知管理员 |

### 4.6 pending 索引构建与延迟清理策略

为避免"先删旧向量再写新向量"导致的中途失败不可用问题，采用 pending 索引构建 + 成功发布后清理策略：

```
1. 保留当前 active Embedding + active 索引不变
2. 将新 Embedding 写入 ai.pending.embedding，并生成 pending 索引版本号（如 "v3"）
3. 用 pending Embedding 批量生成向量，写入 pending 索引
   - 维度不变：可写入同一 Milvus collection，使用 indexVersion/version 区分
   - 维度变化：创建新的 Milvus collection，避免同 collection 维度不兼容
4. pending 索引全部写入并校验成功后：
   a. 将 ai.pending.embedding 发布为 ai.active.embedding
   b. 将 pending 索引标记为 active 索引
   c. 回调 Python /internal/config/reload，使查询侧切换到新的 active Embedding
   d. 清理上一版 active 索引
   e. 清空 ai.pending.embedding，状态切回 NORMAL
5. 如果 pending 索引构建失败：
   a. 删除已写入的 pending 索引残留
   b. ai.active.embedding 和 active 索引保持不变
   c. 状态切为 REINDEX_FAILED 或回退 NORMAL，并记录失败原因
   d. RAG 检索继续使用旧 active 索引，服务不中断
```

> **关键保证**：旧索引在新索引真正发布为 active 之前绝不删除。只有 pending 索引完整写入、校验通过并完成查询侧切换后，系统才清理上一版 active 索引。

### 4.7 供应商删除保护

删除供应商前必须检查是否被当前生效配置引用：

```
管理员点击"删除" → 后端检查：
  ├─ ai.active.chat.providerId == 该供应商？→ 拒绝，提示"当前 Chat 模型正在使用此供应商"
  ├─ ai.active.embedding.providerId == 该供应商？→ 拒绝，提示"当前 Embedding 模型正在使用此供应商"
  └─ 均未引用 → 确认删除 → 删除 ai.provider.{id} 配置行
```

### 4.8 手动重新向量化

在 Embedding 配置区域提供"重新向量化"按钮，不改模型也可以触发。适用场景：知识库内容更新后需要刷新向量索引。

```
管理员点击"重新向量化"
  ↓
二次确认："将使用当前 Embedding 模型 (text-embedding-v4) 重新向量化所有已发布知识库，预计耗时 3-5 分钟"
  ↓
触发与 4.3 相同的 pending 索引构建流程（维度不变，仅刷新向量内容）
```

---

## 五、后端接口设计

### 5.1 Java 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/admin/system/ai-config` | 获取当前配置（apiKey 掩码） |
| `PUT` | `/api/admin/system/ai-config` | 更新当前生效配置 |
| `GET` | `/api/admin/system/ai-config/providers` | 获取所有供应商列表 |
| `POST` | `/api/admin/system/ai-config/providers` | 添加供应商 |
| `PUT` | `/api/admin/system/ai-config/providers/{id}` | 更新供应商 |
| `DELETE` | `/api/admin/system/ai-config/providers/{id}` | 删除供应商（含引用检查） |
| `POST` | `/api/admin/system/ai-config/providers/{id}/test` | 测试供应商连接 |
| `POST` | `/api/admin/system/ai-config/providers/{id}/sync-models` | 同步模型列表 |
| `POST` | `/api/admin/system/ai-config/detect-dimension` | 检测 Embedding 维度 |
| `POST` | `/api/admin/system/ai-config/re-vectorize` | 手动触发重新向量化 |

### 5.2 Python 内部接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/internal/ai-config` | 返回原始配置（apiKey 明文，仅内部调用） |
| `POST` | `/internal/config/reload` | 热重载配置 |
| `GET` | `/internal/config/pending` | 获取待重载配置（Python 启动时主动拉取） |
| `POST` | `/internal/ai-config/test-provider` | 测试供应商连接 + 拉取模型列表 |
| `POST` | `/internal/ai-config/detect-dimension` | 检测 Embedding 维度 |
| `POST` | `/internal/rag/re-vectorize` | 全量重新向量化（pending 索引构建 + 成功发布后清理） |
| `GET` | `/internal/rag/re-vectorize/progress` | 向量化进度查询 |

### 5.3 进度查询响应模型

```json
{
  "status": "REINDEXING",
  "activeIndexVersion": "v2",
  "pendingIndexVersion": "v3",
  "totalChunks": 500,
  "completedChunks": 320,
  "failedChunks": 2,
  "progressPercent": 64,
  "startedAt": "2026-06-03T10:00:00",
  "estimatedRemainingSeconds": 120
}
```

### 5.4 pending 向量化请求补充字段

现有知识库向量化链路已经传入 `datasourceId`、`metadataSnapshotId`、`knowledgeVersionNo`、`previousVersionNo` 和 `chunks`。为了支持 Embedding 切换时的 pending 索引构建，Java 调用 Python `/internal/rag/vectorize` 或 `/internal/rag/re-vectorize` 时需额外携带：

```json
{
  "isPending": true,
  "indexVersion": "v3",
  "targetCollection": "schema_knowledge_1536_v3",
  "targetDimension": 1536,
  "embeddingConfig": {
    "providerId": "openai",
    "baseUrl": "https://api.openai.com/v1",
    "apiKey": "sk-***internal-plain***",
    "model": "text-embedding-3-small",
    "dimension": 1536
  }
}
```

> `embeddingConfig.apiKey` 仅允许 Java → Python 内部调用传递，不能返回给前端。普通知识库发布且 Embedding 未切换时，可继续省略这些字段，由 Python 使用当前 active 配置。

---

## 六、前端页面设计

### 6.1 页面布局

```
┌─────────────────────────────────────────────────────────────┐
│  AI 服务配置                                    [刷新]       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─ 当前生效配置 ──────────────────────────────────────┐    │
│  │                                                     │    │
│  │  🤖 Chat 模型                                       │    │
│  │  供应商: 通义千问    模型: qwen-plus                  │    │
│  │  温度: 0.3          超时: 120s       重试: 2 次      │    │
│  │                                                     │    │
│  │  📐 Embedding 模型                                   │    │
│  │  供应商: 通义千问    模型: text-embedding-v4          │    │
│  │  维度: 1024         上次向量化: 2026-06-01           │    │
│  │  [重新向量化]                                        │    │
│  │                                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─ 供应商管理 ────────────────────────────────────────┐    │
│  │                                                     │    │
│  │  [+ 添加供应商]                                      │    │
│  │                                                     │    │
│  │  ┌ 通义千问 ──────────────────────────────────────┐  │    │
│  │  │ Base URL: https://dashscope.aliyuncs.com/...   │  │    │
│  │  │ API Key: sk-****xxxx     [修改] [测试连接]      │  │    │
│  │  │ 状态: ● 连接正常    上次测试: 10:00             │  │    │
│  │  │                                               │  │    │
│  │  │ Chat 模型 (3):                                 │  │    │
│  │  │   qwen-plus, qwen3.7-max, qwen-turbo          │  │    │
│  │  │ Embedding 模型 (1):                            │  │    │
│  │  │   text-embedding-v4 (1024维)                   │  │    │
│  │  │                                               │  │    │
│  │  │ [同步模型列表]  [编辑]  [删除]                  │  │    │
│  │  └───────────────────────────────────────────────┘  │    │
│  │                                                     │    │
│  │  ┌ OpenAI ────────────────────────────────────────┐  │    │
│  │  │ ...                                            │  │    │
│  │  └───────────────────────────────────────────────┘  │    │
│  │                                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─ 模型选择 ──────────────────────────────────────────┐    │
│  │                                                     │    │
│  │  Chat 模型:                                          │    │
│  │  供应商: [通义千问 ▼]  模型: [qwen-plus ▼]           │    │
│  │  温度: [===0.3===]  超时: [120]s  重试: [2]次        │    │
│  │                                                     │    │
│  │  Embedding 模型:                                     │    │
│  │  供应商: [通义千问 ▼]  模型: [text-embedding-v4 ▼]   │    │
│  │  维度: 1024 (自动检测，可手动覆盖)                   │    │
│  │                                                     │    │
│  │  [保存配置]                                          │    │
│  │                                                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  ┌─ 向量化进度（仅重建时显示）─────────────────────────┐    │
│  │  ████████████████░░░░░░░░  64% (320/500 chunks)    │    │
│  │  状态: 重建中    预计剩余: 2 分钟                    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 交互要点

| 场景 | 交互行为 |
|------|---------|
| 选择 Chat 供应商 | 联动更新模型下拉列表（只显示该供应商的 chatModels） |
| 选择 Embedding 供应商 | 联动更新模型下拉列表（只显示该供应商的 embeddingModels） |
| Embedding 模型变更 | 自动检测维度 + 弹出警告对话框 |
| 测试连接 | 显示 loading → 成功/失败 toast |
| 同步模型列表 | 调用供应商 API 拉取最新模型，更新本地缓存 |
| 保存配置 | 如果 Embedding 变了 → 二次确认 → 否则直接保存 |
| 删除供应商 | 如果被引用 → 拒绝并提示 → 否则二次确认后删除 |
| 向量化进行中 | 页面顶部显示进度条，禁止再次触发向量化操作 |
| 重新向量化 | 二次确认 → 触发 → 显示进度 |

---

## 七、技术实现要点

### 7.1 API Key 加密存储

复用项目已有的 `DatasourceSecretService`（AES-256-GCM），API Key 加密后存入 `sys_config` 的 `config_value` 字段。前端 GET 时返回掩码（前 4 + `****` + 后 4），PUT 时如果 `apiKey` 字段为空则不更新。

### 7.2 配置热重载链路

```
Java PUT → 更新 sys_config
  → 回调 Python POST /internal/config/reload
  ├─ 成功 → Python 清缓存 + 重建实例 → 返回 200
  └─ 失败 → Java 标记 reloadPending=true
     ↓
Python 重启后 → 调用 GET /internal/config/pending
  → 发现 reloadPending=true → 执行 reload → 清除标记
```

### 7.3 Embedding 模型切换与 pending 重新向量化

切换 Embedding 模型需要：

1. **保留 active**：当前 `ai.active.embedding` 和 active 索引继续服务查询
2. **写入 pending**：将新 Embedding 配置写入 `ai.pending.embedding`，将 `ai.vectorize.status` 设为 `REINDEXING`
3. **生成新索引**：生成 pending 索引版本号；维度不变时使用 version 区分，维度变化时创建新 collection
4. **重新分块**：从 `knowledge_chunk` 表读取所有已发布且 APPROVED 的 chunk
5. **重新向量化**：用 pending Embedding 批量生成向量并写入 pending 索引
6. **发布切换**：全部成功后，pending 发布为 active，查询侧热重载，随后删除上一版 active 索引
7. **失败回滚**：中途失败时清理 pending 索引残留，active 配置和 active 索引保持不变

> **关键保证**：旧索引在新索引真正成为 active 前不会删除。重建期间查询继续使用旧 active 索引；新索引失败不会影响现有问答能力。

### 7.3.1 Python 端适配约束

当前 Python 端 `dataocean.infra.embeddings` 使用全局 `settings.qwen_embedding_model` 和单例 `OpenAIEmbeddings`，`rag.service` 的查询侧和 `rag.vectorizer` 的写入侧共用同一组 Embedding 配置。因此实现 pending 索引时需要显式拆分：

1. **查询侧只用 active**：`embed_single()` 继续服务 RAG 检索，只读取 `ai.active.embedding` 热重载后的配置。
2. **重建侧使用 pending 专用实例**：新增按参数创建 Embedding 实例的能力，如 `embed_texts_with_config(texts, embeddingConfig)`，用于 pending 索引构建，不修改全局 `settings`，不清空 active 查询侧缓存。
3. **发布成功后才 reload active**：pending 索引完整写入并校验成功后，Java 再将 pending 发布为 active，并调用 `/internal/config/reload`，此时 Python 查询侧才切换到新 Embedding。
4. **缓存 key 包含供应商信息**：Chat/Embedding 缓存 key 不应只包含 `model`，还应包含 `providerId/baseUrl/model/temperature` 等字段，避免不同供应商存在同名模型时复用错误实例。
5. **Milvus collection 支持动态维度**：当前 `milvus_collection_name` 和 `embedding_dimension` 是全局配置；维度变化时需要新增 `get_collection(collectionName)` / `ensure_collection(collectionName, dimension)`，由 pending 任务指定目标 collection。
6. **向量化请求携带目标索引信息**：Java 调用 Python `/internal/rag/vectorize` 或 `/internal/rag/re-vectorize` 时，需要传入 `embeddingConfig`、`targetCollection`、`targetDimension`、`indexVersion`、`isPending=true`，Python 不应自行读取 active 配置来生成 pending 向量。

### 7.4 模型列表自动拉取与分类

调用供应商的 `GET /v1/models` 接口（OpenAI 兼容端点标准），解析返回的模型列表，按 `model.id` 关键词初步分类：

- 包含 `embedding` 关键词的 → embeddingModels（默认）
- 其余 → chatModels（默认）

**手动调整**：自动分类结果保存后，管理员可在前端手动调整模型分类（将误分类的模型在 Chat/Embedding 之间拖拽）。手动调整结果持久化到供应商配置中，后续同步模型列表时保留手动分类，仅对新增模型使用自动分类。

### 7.5 维度检测优先级

1. **首选**：从 `GET /v1/models` 响应的 `model.dimensions` 或 `model.embedding_dimensions` 字段获取
2. **次选**：调用 Embedding API 对测试文本 `"DataOcean dimension detection test"` 生成向量，取向量长度
3. **兜底**：返回 null，前端提示用户手动输入

### 7.6 供应商存储拆分

每个供应商独立存储为 `ai.provider.{id}` 行，好处：

- 并发编辑不同供应商不会冲突（各写各的行）
- 删除供应商只需删除对应行，无需读写整个 JSON 数组
- 单供应商查询无需反序列化全量供应商列表

---

## 八、与已有模块的关系

| 模块 | 关系 |
|------|------|
| `AiConfig.vue`（已实现） | 将被重构为新的多供应商版本 |
| `sys_config` 表 | 复用，新增 `ai.provider.{id}` 等 key（从旧 `ai.providers` 迁移） |
| `DatasourceSecretService` | 复用，用于 API Key AES-256-GCM 加解密 |
| `config_api.py`（已实现） | 扩展，支持多供应商配置拉取 + `reload_pending` 标记处理 |
| `config.py reload_config()`（已实现） | 复用，清除缓存重建实例 |
| `llm.py` | 扩展缓存 key，支持多供应商同名模型隔离 |
| `embeddings.py` | 新增 pending 专用向量化入口，支持按 `embeddingConfig` 临时创建 Embedding 实例 |
| `RAG retriever` | 查询侧只读取 active Embedding + active 索引；重建期间不使用 pending 索引 |
| `RAG vectorize` | 改造为 pending 索引构建策略（pending 写入 + 成功发布 + 延迟清理旧索引） |
| Python RAG `chunker.py` | 负责 skills.md 分块；Java 仅保存返回的 chunk snapshot |

---

## 九、实施计划

| 阶段 | 内容 | 工作量 |
|------|------|--------|
| **P0** | 数据模型重构（供应商拆行 + Chat/Embedding 分离 + active/pending 向量化状态） | 0.5 天 |
| **P1** | Java 后端接口（供应商 CRUD + 配置更新 + 热重载 + 删除保护 + pending 发布/回滚 + 进度查询） | 1 天 |
| **P2** | Python 端扩展（测试连接 + 维度检测 + pending 向量化 + 进度上报 + pending 拉取） | 1.5 天 |
| **P3** | 前端页面（供应商管理 + 模型选择 + Embedding 切换警告 + 进度条 + 手动分类） | 1.5 天 |
| **P4** | 数据迁移（旧 `ai.providers` 拆分为 `ai.provider.{id}`）+ 端到端联调 | 0.5 天 |

**总计：约 5 天**

---

## 十、验收标准

1. 管理员可新增、编辑、删除未被引用的 AI 供应商，API Key 入库存储为密文，前端只展示掩码。
2. Chat 模型切换后，Java 更新 active 配置并通知 Python 热重载，后续查询使用新 Chat 模型。
3. Embedding 模型切换后，新配置进入 `ai.pending.embedding`，`ai.active.embedding` 不变，查询仍使用旧 active 索引。
4. pending 索引构建期间，前端展示进度和提示："当前查询仍使用上一版索引，新配置将在重建完成后生效"。
5. pending 索引完整写入并校验成功后，系统发布 pending 为 active，Python 查询侧热重载到新 Embedding，并清理上一版旧 active 索引。
6. pending 索引构建失败时，系统清理 pending 残留，active 配置和 active 索引保持不变，问答能力不受影响。
7. Embedding 维度变化时，系统创建新 Milvus collection；不得向旧维度 collection 写入不同维度向量。
8. 供应商模型列表同步支持自动分类和手动修正；后续同步保留管理员手动分类结果。
9. `/api/query/ask` 在 `REINDEXING` 期间仍可用，但返回结果应带提示标记，说明当前使用上一版索引。
10. 端到端联调截图保存到 `output/playwright/`，至少覆盖 Chat 切换、Embedding pending 重建成功、Embedding pending 重建失败三个场景。
