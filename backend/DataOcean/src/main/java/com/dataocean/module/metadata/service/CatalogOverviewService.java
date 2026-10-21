package com.dataocean.module.metadata.service;

import com.dataocean.module.metadata.entity.vo.CatalogEntityOverviewVO;

/**
 * 数据对象详情聚合读取。
 *
 * <p>聚合只读展示，不改变术语、标签、审核或权限的主数据职责。</p>
 */
public interface CatalogOverviewService {

    /**
     * 读取表或字段对象详情。各关联区块独立返回 OK/FORBIDDEN/ERROR，单个区块失败不影响其它区块。
     */
    CatalogEntityOverviewVO overview(Long userId, Long entityId);
}
