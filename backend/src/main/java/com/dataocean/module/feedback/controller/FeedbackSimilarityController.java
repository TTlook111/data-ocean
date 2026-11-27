package com.dataocean.module.feedback.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.feedback.entity.vo.FeedbackSimilarityCandidateVO;
import com.dataocean.module.feedback.service.FeedbackSimilarityService;
import com.dataocean.module.iam.security.RequirePermission;

import java.util.List;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 处理人主动请求相似候选时才执行向量排序。
 */
@RestController
@RequiredArgsConstructor
public class FeedbackSimilarityController {

    /**
     * 核对原事项及各候选当前可用证据。
     */
    private final FeedbackSimilarityService similarity;

    /**
     * 返回人工核查建议，实际合并继续走独立确认接口。
     */
    @PostMapping("/api/v1/feedback/issues/{id}/similarity")
    @RequirePermission("feedback.assign")
    public Result<List<FeedbackSimilarityCandidateVO>> find(@PathVariable String id) {
        return Result.success(similarity.find(id));
    }
}
