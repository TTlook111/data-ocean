# 006 业务术语与知识文档

本模块把术语定义、字段映射和经过审核的业务事实组织成带来源的文档。Java 保存术语修订、文档版本与审核记录。Python 根据固定输入生成目录或释义候选。使用者可以分别追查一段内容采用了哪次元数据快照、哪次人工审核，以及哪个版本已发布。[文档接口](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/controller/KnowledgeDocController.java)、[文档工作区](../../frontend/src/views/admin/semantics/KnowledgeView.vue)

## 从术语到文档

| 操作 | 现有实现 |
| --- | --- |
| 维护术语 | 草稿保存定义、同义词、关联表字段。提交和审核时按当前修订核对定义、完整映射与来源。下游通过 `approvedFact` 和 `factValid` 读取并复核审核事实。[术语工作流](../../backend/DataOcean/src/main/java/com/dataocean/module/glossary/service/impl/GlossaryTermWorkflowServiceImpl.java) |
| 生成目录 | `batchGenerateFromSnapshot` 基于选定数据源的已发布快照整理表、字段和经过筛选的术语、关系、指标等事实。`KnowledgeDocumentPartitioner` 按主题分篇，目录生成无需读取业务行。[草稿生成](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocPublishService.java)、[文档分篇](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/support/KnowledgeDocumentPartitioner.java) |
| 补充释义 | `generate` 将受限范围的元数据发给 Python，生成可单独审阅的候选。人工选择并确认后，`confirm` 将采纳内容追加到草稿修订。服务在确认或使用时复核候选的来源和依赖。[候选生成与确认](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeSemanticServiceImpl.java)、[Python 释义生成](../../python-service/dataocean/knowledge/semantic.py) |
| 编辑与审核 | 正文变化形成新版本。提交后进入 `PENDING_REVIEW`，通过时记录当前版本和审核意见，拒绝时回到草稿。发布要求正文与已审核的当前版本一致，并校验快照事实和字段治理状态。[草稿编辑](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocCrudService.java)、[审核与发布](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocLifecycleService.java) |
| 比较与回滚 | 可查看版本差异和审核记录。对已发布文档执行 `rollback` 会依据已审核的目标版本生成新的回滚草稿，重新进入审核流程。[版本服务](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeVersionServiceImpl.java) |

## 来源与下游使用

业务关系、指标、术语与字段释义各自绑定精确修订和表字段依赖。原始外键与推断关系作为待确认线索。发布校验当前文档的来源，RAG 构建另行核对整个文档组的覆盖和依赖。因此可以分别定位内容修订、审核失效和索引需要重建的原因。[事实校验](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/support/KnowledgeSnapshotFactValidator.java)

知识文档发布只确认该内容版本。数据源级向量构建由有权人员另行确认，构建任务记录所选的快照与文档版本。文档回滚也需要经过新一轮审核、发布和构建才能影响检索结果。[文档发布](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/KnowledgeDocLifecycleService.java)、[RAG 构建服务](../../backend/DataOcean/src/main/java/com/dataocean/module/knowledge/service/impl/RagIndexBuildServiceImpl.java)
