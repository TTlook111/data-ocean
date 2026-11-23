package com.dataocean.module.feedback.service;

import com.dataocean.module.feedback.entity.dto.AssignIssueDTO;
import com.dataocean.module.feedback.entity.dto.ChangeFeedbackTaskDTO;
import com.dataocean.module.feedback.entity.dto.CloseIssueDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackProgressDTO;
import com.dataocean.module.feedback.entity.dto.ReviewIssueDTO;
import com.dataocean.module.feedback.entity.dto.SourceDataResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitIssueResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitKnowledgeResultDTO;
import com.dataocean.module.feedback.entity.dto.VerifyIssueDTO;
import com.dataocean.module.feedback.entity.vo.FeedbackClosurePreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.query.entity.vo.QueryAcceptedVO;

/**
 * 反馈分派、核查、处理结果和复验结案能力。
 */
public interface FeedbackWorkService {

    /**
     * 分派或转派主负责人，沿用现有任务责任。
     */
    MutationVO assign(String id, String command, AssignIssueDTO input);

    /**
     * 添加或转派治理、技术或源数据任务，并以主事项版本防止与其他动作互相覆盖。
     */
    MutationVO assignTask(String id, Integer taskId, String command, AssignIssueDTO input);

    /**
     * 退出时核对剩余影响，恢复时重新核查，两个动作都保留原有处理历史。
     */
    MutationVO changeTaskStatus(String id, int taskId, String command, ChangeFeedbackTaskDTO input);

    /**
     * 当前负责人记录主事项或自己负责的任务核查，旧版本不能覆盖新分派。
     */
    MutationVO review(String id, Integer taskId, String command, ReviewIssueDTO input);

    /**
     * 当前技术负责人确认修复已生效后为对应的处理范围提交结果。
     */
    MutationVO submitResult(String id, Integer taskId, String command, SubmitIssueResultDTO input);

    /**
     * 治理负责人为当前处理范围提交已核实生效的知识版本及覆盖依据。
     */
    MutationVO submitKnowledge(String id, Integer taskId, String command, SubmitKnowledgeResultDTO input);

    /**
     * 保存实际跟进。若当期修正重新进入跟进，则撤回当前结果和复验引用。
     */
    MutationVO followUpSourceData(String id, int taskId, String command, FeedbackProgressDTO input);

    /**
     * 技术修复可保持在办，也可从待验证撤回到跟进。
     */
    MutationVO followUpTechnical(String id, Integer taskId, String command, FeedbackProgressDTO input);

    /**
     * 保存源系统实际修正与生效事实，此操作只写入平台处理台账。
     */
    MutationVO submitSourceData(String id, int taskId, String command, SourceDataResultDTO input);

    /**
     * 针对当前治理结果用原问题发起实际问数，复验人可在工作台查看结果。
     */
    QueryAcceptedVO replayQuestion(String id, Integer taskId, String command);

    /**
     * 人工复验绑定当期处理结果和操作者的源权限，失败退回对应任务。
     */
    MutationVO verify(String id, Integer taskId, String command, VerifyIssueDTO input);

    /**
     * 只读预览确认当前状态、原提问人和将提交的受控回告。
     */
    FeedbackClosurePreviewVO closurePreview(String id, String outcome, String reason);

    /**
     * 结案和回告接收一同落盘，写事务在锁内核对当期复验。
     */
    MutationVO close(String id, String command, CloseIssueDTO input);
}
