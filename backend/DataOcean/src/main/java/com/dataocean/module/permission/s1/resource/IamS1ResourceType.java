package com.dataocean.module.permission.s1.resource;

/**
 * S1 鉴权可解析的资源类型。
 *
 * <p>每种类型必须有且只有一个解析器实现（{@link IamS1ResourceResolver}），
 * 由 {@link IamS1ResourceResolverRegistry} 在启动时校验唯一性。</p>
 */
public enum IamS1ResourceType {

    /** 数据源：输入 datasourceId，读取启用的数据源身份事实。 */
    DATASOURCE,

    /** 元数据快照：输入 snapshotId，解析到 datasourceId。 */
    SNAPSHOT,

    /** 元数据实体：输入 entityId，解析 entity_metadata.datasource_id。 */
    METADATA_ENTITY,

    /** 元数据字段（列实体）：输入 columnId，解析 snapshot → datasourceId。 */
    METADATA_COLUMN,

    /**
     * 数据治理质量问题：输入 issueId，解析到 `metadata_quality_issue` 归属的数据源。
     *
     * <p>问题的 datasourceId 与它所属快照的 datasourceId 必须一致；不一致视为事实断链，拒绝。</p>
     */
    GOVERNANCE_ISSUE,

    /**
     * 语义知识文档：输入 documentId，解析到 `knowledge_doc` 归属的数据源。
     *
     * <p>文档的 datasourceId 必须存在；当前版本与版本上的来源快照若存在，
     * 其归属必须与文档一致，不一致视为事实断链并拒绝。</p>
     */
    KNOWLEDGE_DOCUMENT,

    /**
     * 查询审计记录：输入 auditId，解析到 `query_audit_log.datasource_id`。
     *
     * <p>审计记录不带业务原值，但详情会返回问题与 SQL；归属缺失同样 fail-closed。</p>
     */
    AUDIT_LOG,

    /**
     * 字段元数据（列）：输入 columnMetaId，解析到 `db_column_meta.datasource_id`。
     *
     * <p>字段治理的绝大多数读写都以列为目标，这是它们的统一归属入口。</p>
     */
    COLUMN_META,

    /**
     * 字段标签关系：输入标签关系 ID，解析到它所属列的 `datasource_id`。
     *
     * <p>删除标签时必须按**关系自身**的真实归属判定，不能相信请求里传来的数据源。</p>
     */
    FIELD_TAG_RELATION,

    /**
     * 用户反馈审核：输入 feedbackId，解析到反馈指向的真实数据源。
     *
     * <p>反馈可能挂在字段上（`column_meta_id`），也可能只挂在查询任务上
     * （`query_task_id`）；两者都解析不出归属时 fail-closed。</p>
     */
    FEEDBACK_REVIEW,

    /**
     * 血缘关系：输入 relationshipId，解析到源实体的真实数据源。
     *
     * <p>切面用源端归属做动作准入；源、目标以及列映射两端的逐项负责源校验
     * 必须留在 Service——一条 LINEAGE 边可以跨源，只看源端会放行对目标源的写入。</p>
     */
    LINEAGE_RELATIONSHIP
}
