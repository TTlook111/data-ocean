package com.dataocean.module.governance.service;

import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * 表说明工作者与平台之间仅交换确定结构和可定位候选。
 */
public final class GovernanceGenerationWire {

    /**
     * 内部结构仅由各端已核权的任务入口传递。
     */
    private GovernanceGenerationWire() {}

    /**
     * 处理前临时读取的有界模型上下文。
     *
     * @param objectName 本次表限定名
     * @param sourceComment 采集的真实注释
     * @param columns 获准发送的技术字段
     * @param contentPaths 本次实际生成范围
     * @param notes 已核对当前状态的输入片段
     * @param inputLimits Java 选取阶段触发的裁剪原因
     * @param targetColumn 当前字段的结构，表说明任务为空
     * @param discovery 作用于当前对象的候选发现范围
     */
    public record Context(
            String objectName, String sourceComment, List<Column> columns, List<String> contentPaths,
            List<Note> notes, List<String> inputLimits, Column targetColumn, GovernanceDiscoveryWire.Scope discovery) {}

    /**
     * 源库列的必要技术事实。
     *
     * @param name 真实列名
     * @param type 原生类型
     * @param comment 原注释
     */
    public record Column(String name, String type, String comment) {}

    /**
     * 已授权传输的确切资料输入。
     *
     * @param id 实际资料编号
     * @param body 受控原文
     * @param attachments 允许解析的固定附件字节
     */
    public record Note(String id, String body, List<Attachment> attachments) {}

    /**
     * 允许在限额内解析的固定原件。
     *
     * @param id 原件编号
     * @param name 当前处理格式对应的名称
     * @param data Base64 编码的有界原件
     */
    public record Attachment(String id, String name, String data) {}

    /**
     * Python 已接收、在途或终态的实际状态。
     *
     * @param runVersion 执行代次
     * @param state 执行状态
     * @param failureCode 可披露的失败原因
     * @param result 实际模型结果，成功时才提供
     */
    public record Status(int runVersion, String state, String failureCode, Result result) {}

    /**
     * 真实模型输出的受控结构，仍须由 Java 验证业务字段和引用。
     *
     * @param content 模型建议的类型化字段
     * @param evidence 实际采用的出处
     * @param usedNoteIds 本次实际取用的限定资料
     * @param call 实际模型的调用诊断
     * @param inputLimits 本次调用的受控裁剪原因
     * @param candidates 候选发现时实际返回的列表
     */
    public record Result(
            JsonNode content, List<Evidence> evidence, List<String> usedNoteIds, Call call,
            List<String> inputLimits, List<GovernanceDiscoveryWire.Proposal> candidates) {
    }

    /**
     * 模型适配器报告的诊断元数据，不包含提示词及响应原文。
     *
     * @param revisionId 取用的实际配置修订
     * @param modelName 提供方报告的实际模型
     * @param providerCallId 提供方安全调用编号，可空
     * @param elapsedMs 调用耗时，单位毫秒
     * @param inputTokens 已知输入 token 数
     * @param outputTokens 已知输出 token 数
     */
    public record Call(String revisionId, String modelName, String providerCallId, Integer elapsedMs,
                       Integer inputTokens, Integer outputTokens) {
    }

    /**
     * 只在本次实际输入里验证的资料引用。
     *
     * @param contentPath 受支持的具体业务内容项
     * @param noteId 固定任务实际使用的资料编号
     * @param attachmentId 可能的原件编号
     * @param start 正文范围开始
     * @param end 正文范围结束
     */
    public record Evidence(String contentPath, String noteId, String attachmentId, Integer start, Integer end) {}
}
