package com.dataocean.module.governance.entity.vo;

import com.dataocean.common.PageVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Scope;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 同一成功目录版本的表列表和范围。
 */
@Data
@AllArgsConstructor
public class GovernanceTablePageVO {

    /**
     * 筛选后的表清单及分页。
     */
    private PageVO<GovernanceTableRowVO> page;

    /**
     * 当前成功快照。
     */
    private String currentSnapshotId;

    /**
     * 当前目录版本。
     */
    private int catalogVersion;

    /**
     * 是否需采集或已陈旧。
     */
    private GovernanceFreshnessVO freshness;

    /**
     * 当前正式范围。
     */
    private Scope effectiveScope;

    /**
     * 上次成功快照范围。
     */
    private Scope snapshotScope;
}
