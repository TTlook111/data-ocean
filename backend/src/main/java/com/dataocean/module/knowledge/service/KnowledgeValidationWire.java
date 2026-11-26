package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Verified;
import com.dataocean.module.model.service.ModelInvocation;

import java.util.List;

/**
 * 逐题召回只在已建成的候选索引代次中运行。
 */
public final class KnowledgeValidationWire {

    /**
     * 内部协议由下列类型表达。
     */
    private KnowledgeValidationWire() {}

    /**
     * 仅发送通过授权核对的题干，预期和实际数据不交给工作者。
     */
    public record Question(String id, String text) {}

    /**
     * Java 针对本轮固定的索引与模型许可。
     */
    public record Input(
            String candidateId,
            String documentRevisionId,
            String fingerprint,
            String collection,
            List<Verified> fragments,
            List<Question> questions,
            ModelInvocation model,
            int remainingSeconds) {

        /**
         * 保留任务定位，隐藏正文和凭据。
         */
        @Override
        public String toString() {
            return "KnowledgeValidationInput[candidateId=" + candidateId + "]";
        }
    }

    /**
     * 每道题实际命中的片段及摘要。
     */
    public record Hit(String id, List<Verified> fragments) {}

    /**
     * 工作结果只携带可复核的召回证明。
     */
    public record Receipt(String collection, String fingerprint, List<Hit> matches) {}

    /**
     * 可靠工作队列的状态和确切结果。
     */
    public record Status(int runVersion, String state, String failureCode, Receipt result) {}
}
