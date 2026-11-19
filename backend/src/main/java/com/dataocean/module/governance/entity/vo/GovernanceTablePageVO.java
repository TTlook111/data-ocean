package com.dataocean.module.governance.entity.vo;

import com.dataocean.common.PageVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO.Scope;

/**
 * 同一成功目录版本的表列表和范围。
 *
 * @param page 筛选后的表清单及分页
 * @param currentSnapshotId 当前成功快照
 * @param catalogVersion 当前目录版本
 * @param freshness 是否需采集或已陈旧
 * @param effectiveScope 当前正式范围
 * @param snapshotScope 上次成功快照范围
 */
public record GovernanceTablePageVO(
        PageVO<GovernanceTableRowVO> page,
        String currentSnapshotId,
        int catalogVersion,
        GovernanceFreshnessVO freshness,
        Scope effectiveScope,
        Scope snapshotScope) {}
