package com.dataocean.module.knowledge.service;

import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.model.service.ModelInvocation;

import java.util.List;

/**
 * 正文生成的固定输入与结构化内部回包。
 */
final class KnowledgeDraftGenerationWire {

    /**
     * 内部协议只包含记录类型。
     */
    private KnowledgeDraftGenerationWire() {}

    /**
     * 独立于凭据的正文生成快照。
     *
     * @param configuration 已核对的来源连接修订
     * @param brief 已核对的文档职责和依据
     * @param original 更新建议所依据的原文档修订
     */
    record Snapshot(Long configuration, Brief brief, Original original) {}

    /**
     * 模型使用的业务范围，以及主动根条目和已确认必要依赖。
     *
     * @param title 核对后的文档名称
     * @param purpose 核对后的用途
     * @param questions 已规划的问题样例
     * @param exclusions 已规划的排除条件
     * @param gaps 仅供识别未知边界的待补依据
     * @param facts 当前生成冻结的实际确认内容
     */
    record Brief(String title, String purpose, List<String> questions, List<String> exclusions, List<String> gaps,
            List<Basis> facts) {}

    /**
     * 模型按序号指向范围内的确认条目。
     *
     * @param number 当前生成任务的序号
     * @param selected 是否为用户确认的主动范围
     * @param fact 当前有效的治理内容
     */
    record Basis(int number, boolean selected, GovernanceFactVO fact) {}

    /**
     * 更新任务绑定原章内容及并发版本。
     *
     * @param id 原文档编号
     * @param revisionId 原正文修订
     * @param version 发起时的文档聚合版本
     * @param sections 原人工维护的有序章节
     */
    record Original(String id, String revisionId, int version, List<KnowledgeDocumentVO.Section> sections) {}

    /**
     * 实际模型调用只接收必要的治理资料及更新对照章节。
     *
     * @param taskId 模型调用账本使用的任务编号
     * @param model 已固定的正文节点模型凭据
     * @param brief 本轮正文所用范围
     * @param original 更新时传入原章节
     */
    record Request(String taskId, ModelInvocation model, Brief brief, List<KnowledgeDocumentVO.Section> original) {}

    /**
     * Python 返回的结构化建议。
     *
     * @param changes 各完整章节的替换或新增建议
     */
    record Response(List<Change> changes) {}

    /**
     * 单个建议不携带持久化身份，章节引用由 Java 映射。
     *
     * @param sectionIndex 替换的已有章节序号，新增时为空
     * @param title 建议章节名
     * @param body 建议正文
     * @param basis 章节实际采用的主动依据序号
     */
    record Change(Integer sectionIndex, String title, String body, List<Integer> basis) {}
}
