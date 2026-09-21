package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 用户角色绑定与后台负责源关系实体。 */
@Data
@TableName("iam_s1_role_datasource")
public class IamS1RoleDatasource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userRoleId;
    private Long datasourceId;
    private Integer status;
    private Long revisionNo;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
