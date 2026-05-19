package com.dataocean.module.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("datasource_access")
public class DatasourceAccess {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Long userId;
    private Long grantedBy;
    private LocalDateTime grantedAt;
    private LocalDateTime expiresAt;
}
