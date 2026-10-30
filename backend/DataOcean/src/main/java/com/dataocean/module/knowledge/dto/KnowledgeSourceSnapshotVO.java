package com.dataocean.module.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档版本的来源快照视图对象。
 * <p>
 * 来源快照记录在各版本的 `knowledge_doc_version.metadata_snapshot_id` 上，此前前端要么只
 * 能显示裸 ID，要么需要「取版本列表 + 取数据源快照列表」两次请求再自行关联，且当引用的快照
 * 不在已加载的分页范围内时关联不上（跨数据源的版本尤其如此）。本 VO 是该关联的服务端形态。
 * </p>
 * <p>
 * 按**版本**逐条返回（而不是按快照去重），这样调用方既能看到「哪个版本来自哪个快照」，
 * 也能自行去重。
 * </p>
 *
 * @author DataOcean
 */
@Data
@Builder
public class KnowledgeSourceSnapshotVO {

    /** 文档版本号 */
    private Integer versionNo;

    /** 来源快照 ID */
    private Long snapshotId;

    /** 快照版本号（快照已被删除时为 null） */
    private Integer snapshotVersion;

    /** 快照状态（参见 SnapshotStatus） */
    private String status;

    /** 快照中的表数量 */
    private Integer tableCount;

    /** 快照中的字段数量 */
    private Integer columnCount;

    /** 快照创建时间 */
    private LocalDateTime createdAt;
}
