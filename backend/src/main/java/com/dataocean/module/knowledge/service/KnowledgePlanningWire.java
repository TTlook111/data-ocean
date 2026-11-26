package com.dataocean.module.knowledge.service;

import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO.DocumentReference;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO.Suggestion;
import com.dataocean.module.model.service.ModelInvocation;

import java.util.List;

/**
 * 规划固化依据、模型发起和回包共用的内部协议。
 */
final class KnowledgePlanningWire {

    /**
     * 此类型只持有协议定义。
     */
    private KnowledgePlanningWire() {}

    /**
     * 固定实际来源和已经确认的资料，凭据不落入计划快照。
     *
     * @param configuration 数据源当前连接修订
     * @param goal 用户明确的角色或业务问题
     * @param sourceName 当前来源名称
     * @param facts 已确认的同源治理条目
     * @param existingDocuments 已建立的同源文档
     */
    record Snapshot(
            Long configuration,
            String goal,
            String sourceName,
            List<Fact> facts,
            List<DocumentReference> existingDocuments) {}

    /**
     * 模型只用快照内序号引用原定义。
     *
     * @param number 本轮事实序号
     * @param fact 经过治理服务检查的确认定义
     */
    record Fact(int number, GovernanceFactVO fact) {}

    /**
     * 提交受认证的内部生成请求。
     *
     * @param taskId 实际规划任务号
     * @param model 节点当前允许运行的固定修订
     * @param planning 冻结的源、需求和依据
     */
    record Request(String taskId, ModelInvocation model, Snapshot planning) {}

    /**
     * Python 返回的待用户核对建议。
     *
     * @param documents 以本次治理快照为依据的文档建议
     */
    record Response(List<Suggestion> documents) {}
}
