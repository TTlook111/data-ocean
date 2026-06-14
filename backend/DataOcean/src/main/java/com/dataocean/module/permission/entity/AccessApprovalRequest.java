package com.dataocean.module.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据访问审批请求实体。
 * <p>
 * 用户因 MASK 结果申请查看原始数据，管理员审批后生成临时 ALLOW 策略。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("access_approval_request")
public class AccessApprovalRequest {

    /** 状态：待审批 */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：已通过 */
    public static final String STATUS_APPROVED = "APPROVED";

    /** 状态：已拒绝 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 状态：已过期 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人 ID */
    private Long requesterId;

    /** 数据源 ID */
    private Long datasourceId;

    /** 表名 */
    private String tableName;

    /** 列名（NULL 表示表级申请） */
    private String columnName;

    /** 申请理由 */
    private String requestReason;

    /** 申请时长（小时） */
    private Integer requestedDuration;

    /** 状态 */
    private String status;

    /** 审批人 ID */
    private Long approverId;

    /** 审批时间 */
    private LocalDateTime approvedAt;

    /** 临时策略过期时间 */
    private LocalDateTime expiresAt;

    /** 拒绝理由 */
    private String rejectReason;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
