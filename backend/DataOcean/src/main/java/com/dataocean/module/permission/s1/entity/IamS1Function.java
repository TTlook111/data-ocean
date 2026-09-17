package com.dataocean.module.permission.s1.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** IAM-SIMPLE-1 固定功能目录实体。 */
@Data
@TableName("iam_s1_function")
public class IamS1Function {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String functionCode;
    private String functionName;
    private String functionDescription;
    private String businessDomain;
    private String workspace;
    private String status;
    private String dependencyCodes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
