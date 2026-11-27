package com.dataocean.module.feedback.service;

import com.dataocean.module.feedback.entity.vo.FeedbackSimilarityCandidateVO;

import java.util.List;

/**
 * 为人工合并提供当前访问范围内的相关事项。
 */
public interface FeedbackSimilarityService {

    /**
     * 使用独立反馈向量配置比较同源原问，保持现有事项状态。
     */
    List<FeedbackSimilarityCandidateVO> find(String id);
}
