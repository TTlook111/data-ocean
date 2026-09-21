package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantSaveDTO;

import java.util.Collection;

/** IAM-SIMPLE-1 数据授权配置服务。 */
public interface IamS1DataGrantService {

    Long createGrant(Long operatorUserId, IamS1DataGrantSaveDTO request);

    void createGrants(Long operatorUserId, Collection<IamS1DataGrantSaveDTO> requests, String reason);

    void updateGrant(Long operatorUserId, Long grantId, IamS1DataGrantSaveDTO request);

    void revokeGrant(Long operatorUserId, Long grantId, String reason);

    /**
     * B4：由访问审批通过生成的个人临时授权。
     * <p>
     * 与管理员直接配置的区别：判定使用 {@code security:approval:review} + 负责源，
     * 而不是 {@code security:permission:manage}；审批人无需本人拥有该业务查询数据。
     * 生成事实仍然写入 {@code iam_s1_data_grant}，来源标记为 APPROVAL 并关联申请 ID。
     * </p>
     *
     * @param reviewerId 审批人
     * @param requestId  IAM-SIMPLE-1 访问申请 ID
     * @param request    授权事实（主体必须是申请人、范围必须是批准范围的子集）
     * @return 生成的 S1 授权 ID
     */
    Long createApprovalGrant(Long reviewerId, Long requestId, IamS1DataGrantSaveDTO request);
}
