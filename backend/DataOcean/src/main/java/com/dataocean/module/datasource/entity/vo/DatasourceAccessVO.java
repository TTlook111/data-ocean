package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源访问授权视图对象
 * <p>
 * 用于管理端展示某个数据源的授权用户列表，包含用户基本信息和授权详情。
 * </p>
 *
 * @author dataocean
 */
@Data
public class DatasourceAccessVO {

    /** 授权记录 ID */
    private Long id;
    /** 数据源 ID */
    private Long datasourceId;
    /** 被授权用户 ID */
    private Long userId;
    /** 被授权用户的登录名 */
    private String username;
    /** 被授权用户的真实姓名 */
    private String realName;
    /** 授权操作人 ID */
    private Long grantedBy;
    /** 授权时间 */
    private LocalDateTime grantedAt;
    /** 授权过期时间，为 null 表示永不过期 */
    private LocalDateTime expiresAt;
}
