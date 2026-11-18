package com.dataocean.module.iam.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员主兼任关系及其结束历史，当前主部门由唯一约束保护。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("iam_user_department")
public final class MembershipPO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 当前人员的稳定标识，客户端自报值不能替代登录身份。
     */
    private Long userId;

    /**
     * 本次查询或操作涉及的稳定部门标识。
     */
    private Long departmentId;

    /**
     * 该成员关系是否为人员唯一的当前主部门。
     */
    private boolean isPrimary;

    /**
     * 建立该成员关系的操作人员标识。
     */
    private Long joinedBy;

    /**
     * 该成员关系建立的 UTC 时刻。
     */
    private LocalDateTime joinedAt;

    /**
     * 结束该成员关系的操作人员标识。
     */
    private Long leftBy;

    /**
     * 成员关系结束的 UTC 时刻，空值表示当前关系。
     */
    private LocalDateTime leftAt;
}
