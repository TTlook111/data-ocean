package com.dataocean.module.versioning.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 快照发布门禁结果。
 * <p>
 * 固定返回来源高风险、可用表、语义高风险三项；来源问题和可用表阻断发布，
 * 语义问题只作为提示，不阻断发布。
 * </p>
 */
@Data
public class SnapshotReleaseGateVO {

    private Long snapshotId;
    private Long datasourceId;
    private String snapshotStatus;
    /** 当前是否没有审核阻断项；不代表状态一定可以流转。 */
    private boolean approvable;
    /** 状态为 APPROVED 且没有发布阻断项。 */
    private boolean publishable;
    private int blockingCount;
    private int warningCount;
    private long unresolvedSourceHigh;
    private long unresolvedSemanticHigh;
    private long validTableCount;
    private LocalDateTime evaluatedAt;
    private List<GateItem> items = new ArrayList<>();

    /**
     * 单个门禁检查项。
     */
    @Data
    public static class GateItem {

        /** SOURCE_HIGH_UNRESOLVED / NO_NORMAL_RECOMMENDED_TABLE / SEMANTIC_HIGH_UNRESOLVED */
        private String code;
        /** SOURCE / STRUCTURE / SEMANTIC */
        private String category;
        private String severity;
        /** PASS / FAIL / WARN */
        private String status;
        private boolean blocksApproval;
        private boolean blocksPublish;
        private long count;
        private String message;
    }
}
