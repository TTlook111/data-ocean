package com.dataocean.module.permission.s1.resource;

/**
 * 资源解析结果：把接口收到的资源 ID 还原成可做负责源判定的归属事实。
 *
 * <p>只承载**归属**，不承载业务原值、连接信息或敏感字段。</p>
 *
 * @param type         资源类型
 * @param resourceId   解析用的资源 ID
 * @param datasourceId 资源真实归属的数据源；为 null 表示归属断链，调用方必须 fail-closed
 * @param snapshotId   快照 ID（快照 / 实体 / 字段类资源可提供）
 * @param tableName    表名（字段类资源可提供）
 * @param columnName   字段名（字段类资源可提供）
 */
public record IamS1ResolvedResource(IamS1ResourceType type,
                                    Long resourceId,
                                    Long datasourceId,
                                    Long snapshotId,
                                    String tableName,
                                    String columnName) {

    /** 归属完整（有 datasourceId）才可用于授权判定。 */
    public boolean hasDatasource() {
        return datasourceId != null;
    }

    public static IamS1ResolvedResource of(IamS1ResourceType type, Long resourceId, Long datasourceId) {
        return new IamS1ResolvedResource(type, resourceId, datasourceId, null, null, null);
    }
}
