package com.dataocean.module.feedback.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.query.FeedbackIssueQuery;
import com.dataocean.module.feedback.entity.vo.FeedbackIssueDetailVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.query.entity.vo.QuerySourceOptionVO;

import java.util.List;

/**
 * 反馈队列、候选人与事项详情的读取能力。
 */
public interface FeedbackIssueQueryService {

    /**
     * 待验证事项先按当前复验资格筛选，列表和计数共享同一读快照。
     */
    PageVO<IssueRow> issues(FeedbackIssueQuery query);

    /**
     * 数据源筛选遵从与列表相同的查看权限。
     */
    List<QuerySourceOptionVO> sources();

    /**
     * 分派候选只返回启用且同时具有反馈查看、处理权限的人员。
     */
    PageVO<UserOptionVO> candidates(PageQuery query, String keyword);

    /**
     * 每次回看实时检查证据及源授权，主事项与任务使用相同的裁剪结果。
     */
    FeedbackIssueDetailVO detail(String id);
}
