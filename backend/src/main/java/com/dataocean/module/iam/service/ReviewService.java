package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;

import java.util.List;

/**
 * 影响核对的业务边界，调用者通过明确类型协作。
 */
public interface ReviewService {

    /**
     * 核对当前动作的完整提案、影响与版本，不在预览阶段写入业务事实。
     */
    ImpactPreviewVO preview(
            String action,
            String target,
            Integer version,
            Object proposal,
            List<ChangeVO> changes,
            List<ImpactCountVO> affected,
            List<String> warnings);

    /**
     * 在正式提交时重核签名、主体、内容、当前版本和到期时间。
     */
    void verify(String action, String target, Integer version, Object proposal, String token);
}
