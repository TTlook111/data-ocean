package com.dataocean.module.feedback.entity.vo;

/**
 * 供处理人进一步核对的同源事项，顺序按当次语义相关性排列。
 *
 * @param id 已核对的候选事项编号
 * @param question 当前调用者可回看的原始问题
 */
public record FeedbackSimilarityCandidateVO(String id, String question) {}
