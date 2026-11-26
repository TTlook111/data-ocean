package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 带固定资料修订的来源复核事项。
 */
@Data
@TableName("gov_note_change")
public class GovernanceNoteChangePO {

    /**
     * 本次来源事项编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 企业说明归属的数据源。
     */
    private Long sourceId;

    /**
     * 操作的实际资料。
     */
    private Long noteId;

    /**
     * 正文或附件实质变化或撤下。
     */
    private String kind;

    /**
     * 触发变化的资料聚合版本。
     */
    private Integer noteVersion;

    /**
     * 操作前的确切资料修订。
     */
    private Long beforeRevisionId;

    /**
     * 操作后的确切资料修订，撤下时为空。
     */
    private Long afterRevisionId;

    /**
     * 已定位或有待定位条目。
     */
    private String mappingState;

    /**
     * 人工关联的并发版本。
     */
    private Integer version;

    /**
     * 资料操作的 UTC 毫秒时刻。
     */
    private LocalDateTime occurredAt;
}
