package com.dataocean.module.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.governance.entity.vo.ReviewRecordVO;

/**
 * 元数据治理审核记录服务。
 */
public interface MetadataReviewService {

    /**
     * 分页查询治理审核记录。
     *
     * @param snapshotId 快照 ID
     * @param tableName  可选表名
     * @param page       页码
     * @param size       每页条数
     * @return 审核记录分页结果
     */
    Page<ReviewRecordVO> listRecords(Long snapshotId, String tableName, int page, int size);
}
