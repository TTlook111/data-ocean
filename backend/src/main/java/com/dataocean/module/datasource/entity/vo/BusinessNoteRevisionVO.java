package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 资料原文和保存当时的附件快照。
 */
@Data
@AllArgsConstructor
public class BusinessNoteRevisionVO {

    /**
     * 可用于治理引用的不可变修订标识。
     */
    private String revisionId;

    /**
     * 该版内容修订号。
     */
    private int revision;

    /**
     * 该版本标题。
     */
    private String title;

    /**
     * 该版本说明正文。
     */
    private String body;

    /**
     * 提交时刻，UTC。
     */
    private Instant createdAt;

    /**
     * 此版实际提交人。
     */
    private UserOptionVO author;

    /**
     * 此版关联的原文件元数据。
     */
    private List<NoteAttachmentVO> attachments;
}
