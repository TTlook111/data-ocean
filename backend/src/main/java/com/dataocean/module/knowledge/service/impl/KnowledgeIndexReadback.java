package com.dataocean.module.knowledge.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper.Build;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Evidence;
import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Fragment;
import com.dataocean.module.knowledge.service.KnowledgeIndexProperties;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 由内部存储读取服务核验实际完整代次，控制层不能自报索引就绪。
 */
@Component
public class KnowledgeIndexReadback {

    /**
     * 沿用既有机器身份和固定可信地址，部署关闭时不尝试连接。
     */
    private final ObjectProvider<RestClient> worker;

    /**
     * 已成功构建的全部确切片段。
     */
    private final KnowledgeBuildMapper builds;

    /**
     * 读取持久片段中同版的治理证明。
     */
    private final JsonCodec json;

    /**
     * 当前部署准许传输确认证据的来源。
     */
    private final KnowledgeIndexProperties config;

    /**
     * 核验复用现有内部通信，凭据不借浏览器转发。
     */
    public KnowledgeIndexReadback(
            @Qualifier("governancePythonClient") ObjectProvider<RestClient> worker,
            KnowledgeBuildMapper builds,
            JsonCodec json,
            KnowledgeIndexProperties config) {
        this.worker = worker;
        this.builds = builds;
        this.json = json;
        this.config = config;
    }

    /**
     * 正式检查验证整个输出和同版组合，不能仅凭曾经成功过的构建回执。
     */
    public int verify(Build build, long revision, long embedding, String fingerprint) {
        var client = worker.getIfAvailable();
        if (client == null || !config.getModelSourceIds().contains(build.sourceId())) {
            // 无法按当前部署核对时，不给出已就绪确认。
            throw unavailable();
        }
        var fragments = builds.chunks(build.id()).stream()
                .map(chunk -> new Fragment(
                        chunk.ordinal(),
                        chunk.body(),
                        List.of(json.read(chunk.evidence(), Evidence[].class)),
                        chunk.digest()))
                .toList();
        if (fragments.isEmpty() || !Integer.valueOf(fragments.size()).equals(build.verifiedChunks())) {
            // 构建回执与持久化清单自身必须先匹配。
            throw unavailable();
        }
        var proof = new Snapshot(
                Ids.text(build.candidateId()),
                Ids.text(revision),
                fingerprint,
                Ids.text(embedding),
                build.collectionName(),
                fragments);
        try {
            var result = client.post()
                    .uri("/internal/v1/knowledge-index/readback")
                    .body(proof)
                    .retrieve()
                    .body(Readback.class);
            if (result == null
                    || !proof.collection().equals(result.collection())
                    || !proof.fingerprint().equals(result.fingerprint())
                    || result.verified() != fragments.size()) {
                // 只认可核验这个实际代次的完整成功回执。
                throw unavailable();
            }
            return result.verified();
        } catch (RestClientException failure) {
            // 源端错误正文可能含连接信息，仅返回可读业务原因。
            throw unavailable();
        }
    }

    /**
     * 统一呈现无法核实完整代次，不曝光内部存储信息。
     */
    private BizIllegalException unavailable() {
        return new BizIllegalException(ErrorCode.KNOWLEDGE_NOT_READY, "暂未核实索引代次与本批完整内容一致");
    }

    /**
     * 正式核验绑定的集合、配置与确切内容。
     */
    private record Snapshot(
            String candidate,
            String revision,
            String fingerprint,
            String embedding,
            String collection,
            List<Fragment> fragments) {

        /**
         * 内部调用对象不可输出正文。
         */
        @Override
        public String toString() {
            return "PublicationIndexSnapshot[candidate=" + candidate + "]";
        }
    }

    /**
     * 实际存储回读返回的稳定身份及证据数量。
     */
    private record Readback(String collection, String fingerprint, int verified) {}
}
