package com.dataocean.module.permission.s1.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    /**
     * 管理员队列：只返回本人负责数据源的申请，按状态分组**真分页**。
     *
     * <p>原实现对每个负责源各取固定 100 条再合并，一是待审批记录会被同源的已处理记录挤出，
     * 二是没有任何分页入口，超出部分既看不到也处理不了。现改为单条跨源查询 + 数据库分页。</p>
     *
     * @param statusGroup `PENDING`（待审批）/ `HANDLED`（已处理）/ 空（全部）
     */
    Page<IamS1AccessRequestVO> listQueue(Long reviewerId, String statusGroup, int page, int size);

    /** 审批：同意或拒绝；不能审批本人申请。 */
    Long review(Long reviewerId, Long requestId, IamS1AccessReviewDTO request);
}
