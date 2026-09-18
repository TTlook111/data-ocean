package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 新体系访问审批事实。批准范围必须是申请范围的子集。 */
@Data
@TableName("iam_s1_access_approval")
public class IamS1AccessApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long requestId;
    private Long reviewerId;
    private String decision;
    /** 批准字段名列表的 JSON 数组；拒绝时为空。 */
    private String approvedColumnsJson;
    private LocalDateTime approvedValidFrom;
    private LocalDateTime approvedValidUntil;
    /** 通过后生成的 S1 数据授权 ID，便于解释“为什么能查”。 */
    private Long generatedGrantId;
    private String reason;
    private LocalDateTime createdAt;
}
