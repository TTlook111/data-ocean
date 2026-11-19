package com.dataocean.module.governance.entity.vo;

/**
 * 当前任务获准披露的输入摘要。
 *
 * @param noteId 资料稳定编号
 * @param title 使用的确切标题
 * @param revision 固定修订
 * @param state 选入、实际取用或跳过状态
 */
public record GenerationNoteVO(
        String noteId,
        String title,
        int revision,
        String state) {}
