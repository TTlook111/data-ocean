package com.dataocean.module.knowledge.service;

import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseBuildDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeReleaseCancelDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeReleaseDetailVO;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Input;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Progress;

/**
 * 候选构建接收、详情和受认证执行入口。
 */
public interface KnowledgeBuildService {

    /**
     * 用户命令原子固定构建代次和片段。
     */
    MutationVO start(long source, long candidate, String command, KnowledgeReleaseBuildDTO input);

    /**
     * 取消原型定义的未生效候选并保留原因。
     */
    MutationVO cancel(long source, long candidate, String command, KnowledgeReleaseCancelDTO input);

    /**
     * 当前源内精确回读本次任务。
     */
    KnowledgeReleaseDetailVO detail(long source, long candidate);

    /**
     * 当前权限核对后的轻量进度快照。
     */
    Progress progress(long source, long candidate);

    /**
     * 领取当前代次的获准正文及向量配置。
     */
    Input claimInput(String id, int runVersion);

    /**
     * 长步骤开始前检查原任务仍可推进。
     */
    void recheck(String id, int runVersion);
}
