package com.dataocean.module.metadata.entity.vo;

import com.dataocean.module.metadata.entity.po.MetadataEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据对象详情聚合读取结果。
 *
 * <p>各关联区块分别返回状态：有权为空时是 {@code OK + 空列表}，
 * 无权限是 {@code FORBIDDEN}，读取失败是 {@code ERROR}。
 * 不能把“没有权限/读取失败”折叠成空记录，也不能让单个区块失败拖垮整个对象详情。</p>
 */
public record CatalogEntityOverviewVO(
        MetadataEntity entity,
        String entityType,
        DatasourceInfo datasource,
        /** 本次详情所有对象属性、字段列表和字段治理标签实际使用的来源快照。 */
        SnapshotInfo sourceSnapshot,
        /** 当前数据源的发布目标；同步未完成时可能与 sourceSnapshot 不同。 */
        SnapshotInfo publishedSnapshot,
        /** 来源快照是否可以核验；false 时结构/治理区块应显示 UNAVAILABLE。 */
        boolean sourceVerified,
        /** sourceSnapshot 与 publishedSnapshot 是否一致。 */
        boolean sourceMatchesPublished,
        /** 数据来源与当前发布目标的差异说明；无差异时为空。 */
        String sourceMessage,
        TableInfo table,
        ColumnInfo column,
        /** 字段列表是独立区块：来源无法核验时返回 UNAVAILABLE，不伪装成空列表。 */
        Section<ColumnInfo> columns,
        Counts counts,
        Section<RelationItem> structureRelations,
        Section<TermItem> terms,
        Section<TagItem> tags,
        Section<FieldTagItem> fieldTags,
        Section<LineageItem> lineage,
        Section<LineageItem> downstream) {

    public record DatasourceInfo(Long id, String name, String databaseName) {
    }

    public record SnapshotInfo(Long id, Integer version, String status,
                               String entitySyncStatus, String entitySyncError,
                               LocalDateTime entitySyncedAt) {
    }

    public record TableInfo(Long entityId, String tableName, String displayName, String comment,
                            String tableType, String governanceStatus) {
    }

    public record ColumnInfo(Long entityId, Long columnMetaId, String name, String displayName,
                             String dataType, Boolean nullable, Boolean primaryKey, String comment,
                             String governanceStatus, String tableName, Integer ordinalPosition,
                             List<String> usageLimits) {
    }

    public record Counts(int columns, int structureRelations, int terms, int tags,
                         int fieldTags, int lineage, int downstream) {
    }

    public record Section<T>(String status, String message, List<T> items) {

        public static final String OK = "OK";
        public static final String FORBIDDEN = "FORBIDDEN";
        public static final String ERROR = "ERROR";
        public static final String UNAVAILABLE = "UNAVAILABLE";

        public static <T> Section<T> ok(List<T> items) {
            return new Section<>(OK, null, items == null ? List.of() : items);
        }

        public static <T> Section<T> forbidden(String message) {
            return new Section<>(FORBIDDEN, message, List.of());
        }

        public static <T> Section<T> error(String message) {
            return new Section<>(ERROR, message, List.of());
        }

        public static <T> Section<T> unavailable(String message) {
            return new Section<>(UNAVAILABLE, message, List.of());
        }
    }

    public record RelationItem(Long id, String relationType, String direction,
                               String sourceType, Long sourceId, String sourceName,
                               String targetType, Long targetId, String targetName,
                               String bindingStatus) {
    }

    public record TermItem(Long termId, Long glossaryId, String name, String displayName,
                           String description, String status, boolean currentlyValid,
                           String validityMessage, String verificationCode, String linkMode,
                           List<String> viaColumns, Long revision) {
    }

    public record TagItem(Long id, String name, String displayName, String description,
                          String kind, String source, Boolean confirmed, String bindingStatus) {
    }

    public record FieldTagItem(Long id, Long columnMetaId, String tagCode, String tagName, String source) {
    }

    public record LineageItem(Long id, String relationType, String direction,
                              String sourceType, Long sourceId, String sourceName,
                              String targetType, Long targetId, String targetName,
                              String lineageType, String bindingStatus, String description) {
    }
}
