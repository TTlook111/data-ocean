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
    METADATA_COLUMN
}
