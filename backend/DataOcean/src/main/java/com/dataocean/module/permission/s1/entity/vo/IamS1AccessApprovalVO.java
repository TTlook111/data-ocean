package com.dataocean.module.permission.s1.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 访问审批结果。批准范围必须是申请范围的子集，并记录生成的 S1 授权 ID，便于解释“为什么能查”。
 */
public record IamS1AccessApprovalVO(String decision,
                                    String decisionName,
                                    Long reviewerId,
                                    String reviewerName,
                                    List<String> approvedColumns,
                                    LocalDateTime approvedValidFrom,
                                    LocalDateTime approvedValidUntil,
                                    Long generatedGrantId,
                                    String reason,
                                    LocalDateTime createdAt) {
}
