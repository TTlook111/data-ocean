package com.dataocean.module.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dataocean.common.PageVO;
import com.dataocean.module.feedback.entity.dto.FeedbackProgressDTO;
import com.dataocean.module.feedback.entity.query.FeedbackIssueQuery;
import com.dataocean.module.feedback.service.FeedbackIssueLifecycleService;
import com.dataocean.module.feedback.service.FeedbackIssueQueryService;
import com.dataocean.module.feedback.service.FeedbackNoticeService;
import com.dataocean.module.feedback.service.FeedbackService;
import com.dataocean.module.feedback.service.FeedbackWorkService;
import com.dataocean.module.iam.entity.vo.MutationVO;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 验证接口模型调整后仍接收现有队列参数及进展写入请求。
 */
class FeedbackControllerTest {

    /**
     * 接收控制器绑定后的队列条件。
     */
    private final FeedbackIssueQueryService issueQuery = mock(FeedbackIssueQueryService.class);

    /**
     * 接收 JSON 绑定后的跟进写入命令。
     */
    private final FeedbackWorkService work = mock(FeedbackWorkService.class);

    /**
     * 使用 MVC 自身的绑定和 JSON 转换，业务办理规则在对应服务测试中覆盖。
     */
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeedbackController(
                    mock(FeedbackService.class),
                    issueQuery,
                    mock(FeedbackIssueLifecycleService.class),
                    work,
                    mock(FeedbackNoticeService.class)))
            .build();

    /**
     * 现有 URL 的分组、来源、搜索和分页绑定到同一个队列查询对象。
     */
    @Test
    void bindsExistingQueueParameters() throws Exception {
        when(issueQuery.issues(any())).thenReturn(new PageVO<>(List.of(), 101, 11, 10));

        mvc.perform(get("/api/v1/feedback/issues")
                        .param("scope", "MINE")
                        .param("sourceId", "3")
                        .param("keyword", "库存")
                        .param("pageNum", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(101))
                .andExpect(jsonPath("$.data.pageNum").value(11));

        var query = ArgumentCaptor.forClass(FeedbackIssueQuery.class);
        verify(issueQuery).issues(query.capture());
        assertThat(query.getValue().getScope()).isEqualTo("MINE");
        assertThat(query.getValue().getSourceId()).isEqualTo("3");
        assertThat(query.getValue().getKeyword()).isEqualTo("库存");
        assertThat(query.getValue().getPageNum()).isEqualTo(11);
    }

    /**
     * 原任务参数、操作编号和进展字段沿用现有接口契约。
     */
    @Test
    void bindsTechnicalProgressRequest() throws Exception {
        var command = UUID.randomUUID().toString();
        var progress = new FeedbackProgressDTO(4, true, "复查已完成", "等待修复生效");
        when(work.followUpTechnical("3", 2, command, progress))
                .thenReturn(new MutationVO(command, "feedback_issue", "3", 5));

        mvc.perform(
                        post("/api/v1/feedback/issues/3/technical-progress")
                                .param("task", "2")
                                .header("Idempotency-Key", command)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                            {"expectedVersion":4,"waitingExternal":true,"workDone":"复查已完成","nextStep":"等待修复生效"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(5));

        verify(work).followUpTechnical("3", 2, command, progress);
    }
}
