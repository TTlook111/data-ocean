package com.dataocean.module.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限变更审计日志实体。
 * <p>
 * 记录所有权限策略和数据源访问的变更历史，支持审计追溯。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("permission_change_log")
public class PermissionChangeLog {

    /** 变更类型：创建 */
    public static final String TYPE_CREATE = "CREATE";

    /** 变更类型：更新 */
    public static final String TYPE_UPDATE = "UPDATE";

    /** 变更类型：删除 */
    public static final String TYPE_DELETE = "DELETE";

    /** 变更类型：授权 */
    public static final String TYPE_GRANT = "GRANT";

    /** 变更类型：撤销 */
    public static final String TYPE_REVOKE = "REVOKE";

    /** 目标类型：策略 */
    public static final String TARGET_POLICY = "POLICY";

    /** 目标类型：访问授权 */
    public static final String TARGET_ACCESS = "ACCESS";

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 变更类型 */
    private String changeType;

    /** 目标类型 */
    private String targetType;

    /** 目标 ID */
    private Long targetId;

    /** 主体类型 */
    private String subjectType;

    /** 主体 ID */
    private Long subjectId;

    /** 数据源 ID */
    private Long datasourceId;

    /** 变更前值（JSON） */
    private String oldValue;

    /** 变更后值（JSON） */
    private String newValue;

    /** 操作人 ID */
    private Long operatorId;

    /** 变更原因 */
    private String reason;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
