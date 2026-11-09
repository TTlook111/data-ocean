# 009 Schema RAG 构建与检索

该模块把同一数据源、同一元数据快照下已审核发布的知识文档建成专属向量索引，并为问数任务返回当前用户有权使用的知识片段。Java 保存构建清单、片段及活动索引指针，Python 执行切分、向量化、检索和重排。

## 建立和替换索引

1. 知识页单独提交构建确认。服务检查数据源与来源快照的归属、快照的发布历史和已发布文档版本，随后冻结文档清单、Embedding 配置和构建代次。发布文档和确认构建是两个独立操作。[知识页](../../frontend/src/views/admin/semantics/KnowledgeView.vue) · [确认接口](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java) · [构建服务](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/RagIndexBuildServiceImpl.java)
2. 调度器领取排队构建。Java 按清单调用 Python，逐篇检查 Markdown 中标记的事实与快照，将切片和来源存入数据库，再把有审核记录和完整依赖的片段写入该构建专属的 Milvus 集合。[构建调度](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/scheduler/RagIndexBuildScheduler.java) · [内部客户端](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/client/impl/PythonRagClientImpl.java) · [构建接口](../../python-service/dataocean/rag/router.py) · [切分器](../../python-service/dataocean/rag/chunker.py) · [向量写入](../../python-service/dataocean/rag/vectorizer.py)
3. 服务核对写入数量与集合实测数量，在事务内确认当前目标代次和 Embedding 配置一致后切换活动指针。新查询绑定活动构建，已经派发的查询继续使用原构建。旧集合进入清理队列，仍有进行中查询引用时延后删除。[指针切换与清理](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/RagIndexBuildServiceImpl.java)

## 查询检索

S1 检索使用任务绑定的数据源、快照、构建 ID、集合名和权限快照。向量召回、相邻片段扩展和重排给出候选顺序。服务按片段身份与 Java 发来的授权清单匹配，正文和依赖取自该清单。身份冲突的片段整组排除。向量服务不可用时，可用的回退材料也受同样的表、字段和来源限制。[S1 检索入口](../../python-service/dataocean/iam_s1/service.py) · [检索编排](../../python-service/dataocean/rag/service.py) · [Milvus 召回](../../python-service/dataocean/rag/retriever.py) · [重排](../../python-service/dataocean/rag/reranker.py) · [授权过滤](../../python-service/dataocean/iam_s1/firewall.py)
