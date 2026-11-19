package com.dataocean.module.datasource.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 不可变正文快照，状态动作单独记录。
 */
@Data
@TableName("ds_business_note_revision")
public class BusinessNoteRevisionPO {

    /**
     * 资料修订的全局唯一编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 资料稳定编号。
     */
    private Long noteId;

    /**
     * 该资料内的修订序号。
     */
    private Integer revision;

    /**
     * 该版资料标题。
     */
    private String title;

    /**
     * 该版说明正文，可为空。
     */
    private String body;

    /**
     * 该版资料的实际提交人。
     */
    private Long changedBy;

    /**
     * 内容保存时的 UTC 时间。
     */
    private LocalDateTime createdAt;
}
