package com.dataocean.module.knowledge.service;

import com.dataocean.module.model.service.ModelInvocation;

import java.util.List;

/**
 * 构建执行只使用本次任务固定输入及可核对的存储回执。
 */
public final class KnowledgeBuildWire {

    /**
     * 内部消息只通过以下结构传输，不直接构造协议实例。
     */
    private KnowledgeBuildWire() {}

    /**
     * 一个完整章节及其同版治理证据。
     */
    public record Fragment(int ordinal, String body, List<Evidence> evidence, String digest) {}

    /**
     * 固定稳定身份、对象世代和相应确认修订。
     */
    public record Evidence(
            String objectId,
            int generation,
            String kind,
            String targetId,
            String factRevisionId,
            String structureRevisionId) {}

    /**
     * 短期授权的执行输入，业务正文和凭据不进入诊断日志。
     */
    public record Input(
            String candidateId,
            String documentRevisionId,
            String fingerprint,
            String collection,
            String question,
            List<Fragment> fragments,
            ModelInvocation model,
            int remainingSeconds) {

        /**
         * 仅记录可信版本定位，省略业务正文和凭据。
         */
        @Override
        public String toString() {
            return "KnowledgeBuildInput[candidateId=" + candidateId + "]";
        }
    }

    /**
     * 工作者存档的实际向量和 Strong 回读证明，返回元数据而非向量。
     */
    public record Receipt(
            String collection, String fingerprint, int dimension, List<Verified> observed, List<Integer> hits) {}

    /**
     * 回读到的片段标识及核验摘要。
     */
    public record Verified(int ordinal, String digest) {}

    /**
     * 和已部署内部队列一致的任务信封。
     */
    public record Status(int runVersion, String state, String failureCode, Receipt result) {}

    /**
     * 订阅只发送候选版本与阶段，详细结果通过受控详情回读。
     */
    public record Progress(String buildId, int version, String candidateState, String buildState) {}
}
