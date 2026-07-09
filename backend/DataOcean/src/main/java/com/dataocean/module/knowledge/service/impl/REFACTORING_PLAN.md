# KnowledgeDocServiceImpl 拆分计划

## 当前状态
- 文件：`KnowledgeDocServiceImpl.java`
- 行数：776 行
- 职责：CRUD、生命周期管理、AI 生成、数据加载

## 拆分方案

### 1. KnowledgeDocCrudService
- listDocs()
- getDocById()
- createDoc()
- updateDoc()

### 2. KnowledgeDocLifecycleService
- submitReview()
- approve()
- reject()
- publish()

### 3. KnowledgeDocPublishService
- generateDraft()
- batchGenerateFromSnapshot()
- loadTablesMetadata()
- loadFieldTags()
- loadForeignKeys()

## 执行步骤

1. 创建 KnowledgeDocCrudService 接口和实现
2. 创建 KnowledgeDocLifecycleService 接口和实现
3. 创建 KnowledgeDocPublishService 接口和实现
4. 迁移方法到对应 Service
5. 更新 Controller 使用新 Service
6. 更新测试
7. 验证所有功能

## 预计工作量
- 2-3 天
- 需要仔细测试每个功能点
