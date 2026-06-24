package com.dataocean.module.datasource.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源可询问状态视图。
 * <p>
 * 聚合连接、元数据快照、治理问题、知识发布和权限配置状态，
 * 用于前台查询页和后台工作台判断数据源是否已经可以自然语言询问。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceReadinessVO {

    private Long datasourceId;
    private String datasourceName;
    private boolean askable;
    private String stage;
    private String stageLabel;
    private Integer progress;
    private Long publishedSnapshotId;
    private Integer snapshotVersion;
    private Long publishedKnowledgeDocId;
    private Integer knowledgeVersion;
    private boolean connectionReady;
    private boolean metadataReady;
    private boolean governanceReady;
    private boolean knowledgeReady;
    private boolean permissionReady;
    @Builder.Default
    private List<BlockReason> blockReasons = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BlockReason {
        private String code;
        private String message;
        private String ownerRole;
        private String actionText;
        private String actionPath;
    }
}
