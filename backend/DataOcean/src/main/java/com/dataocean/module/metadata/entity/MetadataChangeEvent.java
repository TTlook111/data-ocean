package com.dataocean.module.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 元数据变更事件实体。
 * <p>
 * 记录元数据实体的变更历史，支持事件传播和审计追溯。
 * </p>
 *
 * @author dataocean
 */
@Data
@TableName("metadata_change_event")
public class MetadataChangeEvent {

    /** 事件类型：创建 */
    public static final String EVENT_CREATE = "CREATE";

    /** 事件类型：更新 */
    public static final String EVENT_UPDATE = "UPDATE";

    /** 事件类型：删除 */
    public static final String EVENT_DELETE = "DELETE";

    /** 事件类型：发布 */
    public static final String EVENT_PUBLISH = "PUBLISH";

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件类型 */
    private String eventType;

    /** 实体类型 */
    private String entityType;

    /** 实体 ID */
    private Long entityId;

    /** 实体全限定名 */
    private String entityFqn;

    /** 变更数据（JSON） */
    private String changeData;

    /** 操作人 ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
