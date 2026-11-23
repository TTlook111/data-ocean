package com.dataocean.module.datasource.entity.vo;

import com.dataocean.common.PageVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Scope;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指定来源当前可读取的成功目录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CatalogPageVO {

    /**
     * 实际分页及对象清单。
     */
    private PageVO<ObjectRow> page;

    /**
     * 源当前成功快照编号。
     */
    private String currentSnapshotId;

    /**
     * 目录投影版本。
     */
    private int catalogVersion;

    /**
     * 当前配置范围。
     */
    private Scope effectiveScope;

    /**
     * 当前成功快照的采集范围。
     */
    private Scope snapshotScope;

    /**
     * 当前范围内可阅读对象数。
     */
    private long readableObjectCount;

    /**
     * 来源快照中的单个对象摘要。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ObjectRow {

        /**
         * 已判定的对象稳定标识。
         */
        private String objectId;

        /**
         * 本次实际观察。
         */
        private String observationId;

        /**
         * 归属数据源。
         */
        private String sourceId;

        /**
         * 来源 Schema。
         */
        private String namespace;

        /**
         * 源对象名。
         */
        private String objectName;

        /**
         * 完整限定名。
         */
        private String qualifiedName;

        /**
         * 对象分类。
         */
        private String objectType;

        /**
         * 来源注释。
         */
        private String sourceComment;

        /**
         * 对象的一个或多个目录状态。
         */
        private List<String> catalogStates;

        /**
         * 观察所在快照。
         */
        private String snapshotId;

        /**
         * 本次成功采集时间。
         */
        private Instant collectedAt;
    }
}
