package com.dataocean.module.datasource.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前资料与指定内容修订的业务视图。
 */
@Data
@AllArgsConstructor
public class BusinessNoteDetailVO {

    /**
     * 稳定资料和当前状态。
     */
    private BusinessNoteRowVO note;

    /**
     * 确切修订内容。
     */
    private BusinessNoteRevisionVO content;

    /**
     * 可回看的历史版本号。
     */
    private List<Integer> revisions;

    /**
     * 保留最近撤下的事实。
     */
    private String withdrawalReason;
}
