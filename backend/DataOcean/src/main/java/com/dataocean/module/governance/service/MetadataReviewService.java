package com.dataocean.module.governance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.governance.entity.vo.ReviewRecordVO;

public interface MetadataReviewService {

    Page<ReviewRecordVO> listRecords(Long snapshotId, String tableName, int page, int size);
}
