package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1AccessRequestSubmitDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessReviewDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AccessRequestVO;

import java.util.List;

/**
 * IAM-SIMPLE-1 新体系访问申请与审批。
 * <p>
 * 与旧审批完全隔离：不读取、不复用旧数据授权策略或旧审批记录作为新授权输入；
 * 通过后生成独立的新体系授权事实，并记录批准范围、生成的授权 ID、审批人、理由和时间。
 * </p>
 */
public interface IamS1AccessRequestService {

    /** 业务用户提交申请；待审批申请不授予查询权。 */
    Long submit(Long requesterId, IamS1AccessRequestSubmitDTO request);

    /** 申请人撤回本人待审批申请。 */
    void withdraw(Long requesterId, Long requestId, String reason);

    /** 我的申请：只返回本人申请。 */
    List<IamS1AccessRequestVO> listMine(Long requesterId);

    /** 管理员队列：只返回本人负责数据源的申请。 */
    List<IamS1AccessRequestVO> listQueue(Long reviewerId);

    /** 审批：同意或拒绝；不能审批本人申请。 */
    Long review(Long reviewerId, Long requestId, IamS1AccessReviewDTO request);
}
