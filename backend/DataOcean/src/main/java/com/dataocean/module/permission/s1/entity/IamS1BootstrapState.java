package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 首个系统管理员初始化状态实体。 */
@Data
@TableName("iam_s1_bootstrap_state")
public class IamS1BootstrapState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String protocolVersion;
    private String state;
    private Long targetUserId;
    private LocalDateTime completedAt;
    private String implementationVersion;
    private String executionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
