package com.dataocean.module.versioning.service;

import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.versioning.entity.vo.SnapshotReleaseGateVO;

/**
 * 快照发布门禁服务。
 * <p>
 * 审核、发布和门禁查询共用同一套判定，避免页面显示与接口行为不一致。
 * 来源高风险和可用表阻断发布；语义高风险只提示，不阻断。
 * </p>
 */
public interface SnapshotReleaseGateService {

    /**
     * 按快照 ID 计算门禁结果。
     */
    SnapshotReleaseGateVO evaluate(Long snapshotId);

    /**
     * 使用已加载的快照计算门禁结果，避免重复查询。
     */
    SnapshotReleaseGateVO evaluate(MetadataSnapshot snapshot);

    /**
     * 审核前校验；存在审核阻断项时抛出 409。
     */
    void assertApprovable(MetadataSnapshot snapshot);

    /**
     * 发布前校验；存在发布阻断项时抛出 409。
     */
    void assertPublishable(MetadataSnapshot snapshot);
}
