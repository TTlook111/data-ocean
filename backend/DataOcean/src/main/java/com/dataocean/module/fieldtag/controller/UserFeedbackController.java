package com.dataocean.module.fieldtag.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.fieldtag.entity.dto.FeedbackRequestDTO;
import com.dataocean.module.fieldtag.entity.vo.FeedbackVO;
import com.dataocean.module.fieldtag.service.UserFeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户反馈控制器
 * <p>
 * 提供用户对查询结果中字段的反馈提交 API。
 * 无需特殊权限，登录用户即可提交反馈。
 * </p>
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@Slf4j
public class UserFeedbackController {

    private final UserFeedbackService userFeedbackService;

    /**
     * 提交用户反馈
     *
     * @param request 反馈请求参数
     * @return 反馈结果
     */
    @PostMapping
    public Result<FeedbackVO> submitFeedback(@Valid @RequestBody FeedbackRequestDTO request) {
        return Result.success("反馈提交成功", userFeedbackService.submitFeedback(request));
    }
}
