package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 映射来源身份、采集指针和目录版本，连接凭据存放在独立表中。
 */
@Data
@TableName("ds_source")
public class DatasourcePO {

    /**
     * 数据源稳定编号。
     */
    @TableId(type = IdType.AUTO)
    private long id;

    /**
     * 数据源展示名。
     */
    private String name;

    /**
     * 当前配置状态。
     */
    private String lifecycle;

    /**
     * 当前写版本。
     */
    private int version;

    /**
     * 生效的连接配置编号。
     */
    private Long currentConnectionRevisionId;

    /**
     * 当前成功快照编号。
     */
    private Long currentSnapshotId;

    /**
     * 在途任务编号。
     */
    private String activeTaskId;

    /**
     * 在途凭据更换编号。
     */
    private String pendingChangeId;

    /**
     * 当前目录版本，由目录发布及范围变更递增。
     */
    private int catalogVersion;

    /**
     * 是否暂停后续定时采集。
     */
    private boolean schedulePaused;

    /**
     * 当前目录连续失期的起始时间，UTC。
     */
    private LocalDateTime staleSince;

    /**
     * 被标记为失期的实际采集范围摘要。
     */
    private byte[] staleScopeKey;

    /**
     * 数据源首次创建时由数据库记录的 UTC 毫秒时刻。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /**
     * 数据源最近一次实际业务变更的数据库 UTC 毫秒时刻。
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
