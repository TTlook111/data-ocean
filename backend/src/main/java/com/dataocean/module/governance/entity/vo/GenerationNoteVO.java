package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前任务获准披露的输入摘要。
 */
@Data
@AllArgsConstructor
public class GenerationNoteVO {

    /**
     * 资料稳定编号。
     */
    private String noteId;

    /**
     * 使用的确切标题。
     */
    private String title;

    /**
     * 固定修订。
     */
    private int revision;

    /**
     * 选入、实际取用或跳过状态。
     */
    private String state;
}
