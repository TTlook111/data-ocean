package com.dataocean.module.datasource.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 资料清单中的可披露属性。
 */
@Data
@AllArgsConstructor
public class BusinessNoteRowVO {

    /**
     * 资料稳定编号。
     */
    private String id;

    /**
     * 资料所属数据源。
     */
    private String sourceId;

    /**
     * 数据源名称。
     */
    private String sourceName;

    /**
     * 最近资料标题。
     */
    private String title;

    /**
     * 首次提交人。
     */
    private UserOptionVO author;

    /**
     * 当前资料状态。
     */
    private String state;

    /**
     * 当前变更的并发版本。
     */
    private int version;

    /**
     * 当前内容修订。
     */
    private int revision;

    /**
     * 当前修订的附件数。
     */
    private int attachmentCount;

    /**
     * 全部历史修订受到的引用数量。
     */
    private int referenceCount;

    /**
     * 最近更新时间，UTC。
     */
    private Instant updatedAt;
}
