package com.dataocean.module.feedback.service;

import com.dataocean.module.feedback.entity.dto.DisposeFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackIssueReasonDTO;
import com.dataocean.module.feedback.entity.dto.MergeFeedbackIssueDTO;
import com.dataocean.module.feedback.entity.dto.RequestFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.SubmitFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.vo.FeedbackSupplementVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

/**
 * 原问补充、重复事项合并与结案重开能力。
 */
public interface FeedbackIssueLifecycleService {

    /**
     * 主负责人结束一条待回复的请求。原账号的可用性不影响负责人继续跟进。
     */
    MutationVO disposeSupplement(String id, String supplementId, String command, DisposeFeedbackSupplementDTO input);

    /**
     * 主负责人向组内确切原问发出必要补充请求，两端版本与请求同事务推进。
     */
    MutationVO askSupplement(String id, String command, RequestFeedbackSupplementDTO input);

    /**
     * 员工只读取本次原问关联的内容，后续以当前主事项状态表达处理进度。
     */
    FeedbackSupplementVO ownSupplement(String requestId);

    /**
     * 回复原请求或主动补充本人知识缺口，正文只进入反馈证据。
     */
    MutationVO submitSupplement(String requestId, String command, SubmitFeedbackSupplementDTO input);

    /**
     * 同源且经人工核实的重复事项归入一个仍在办理的主事项。
     */
    MutationVO merge(String id, String command, MergeFeedbackIssueDTO input);

    /**
     * 解除错误合并，原事项的补充、证据和关系历史均继续归原事项。
     */
    MutationVO unmerge(String id, String command, FeedbackIssueReasonDTO input);

    /**
     * 根据新证据开启新完成轮次，复验、任务结果及旧回告继续保留在历史中。
     */
    MutationVO reopen(String id, String command, FeedbackIssueReasonDTO input);
}
