package com.dataocean.module.query.service;

import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.RerankDecision;
import com.dataocean.module.query.service.QueryProtocol.RerankOrder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * 重排只能改变同一批权威候选的次序。
 */
final class QueryReranking {

    /** 与业务权限及外发调用分开，验证只依赖已经读到的候选。 */
    private QueryReranking() {}

    /** 校验回包是完整排列，缺失或无效时固定原召回顺序。 */
    static RerankDecision decide(List<Hit> hits, RerankOrder reply) {
        var order = reply == null ? null : reply.order();
        if (order == null || order.size() != hits.size() || new HashSet<>(order).size() != hits.size()
                || order.stream().anyMatch(index -> index == null || index < 0 || index >= hits.size())) {
            // 不丢弃低分项，排序失败时也不扩展候选。
            return new RerankDecision(hits, false);
        }
        return new RerankDecision(order.stream().map(hits::get).toList(), true);
    }

    /** 恢复时只在仍获准的同一批编号、片段序号及摘要上应用原决定。 */
    static List<Basis> restore(List<Hit> hits, List<Basis> bases, RerankDecision decision) {
        var current = new HashMap<Hit, Basis>();
        for (int i = 0; i < hits.size(); i++) {
            // 对应的是 Java 实际核验的正文，而不是模型返回内容。
            current.put(hits.get(i), bases.get(i));
        }
        var saved = decision.hits();
        if (saved == null || current.size() != hits.size() || saved.size() != hits.size()
                || !current.keySet().equals(new HashSet<>(saved))) {
            // 原规划输入与当前核验候选不一致，不能给旧回执换一批证据。
            throw QueryFailure.technical();
        }
        return saved.stream().map(current::get).toList();
    }
}
