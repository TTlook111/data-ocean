package com.dataocean.module.knowledge.service;

import com.dataocean.module.access.entity.vo.OutputRuleVO;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 部署侧明确准入的知识外发范围和代次名称前缀。
 */
@Getter
@Setter
@Component
@ConfigurationProperties("dataocean.knowledge-index")
public class KnowledgeIndexProperties {

    /**
     * 允许向已分配模型发送确认知识正文的源编号。
     */
    private Set<Long> modelSourceIds = Set.of();

    /**
     * 同一 Milvus 中区分不同部署及验收的代次名称前缀。
     */
    private String collectionPrefix = "dataocean_kn";

    /**
     * 各源经核对与聚合值及确认证据相容的输出规则，使用规则编号/版本。
     */
    private Map<Long, Set<String>> validationAllowedRuleRevisions = Map.of();

    /**
     * 未实现的限制不由模型猜测解释。规则升版后须重新核对。
     */
    public boolean allowsValidationRules(long source, List<OutputRuleVO> rules) {
        var allowed = validationAllowedRuleRevisions.getOrDefault(source, Set.of());
        return rules.stream().allMatch(rule -> allowed.contains(rule.getId() + "/" + rule.getVersion()));
    }

    /**
     * 仅由服务端分配新代次名称，请求无法指定存储位置。
     */
    public String collection(String build) {
        if (collectionPrefix == null || !collectionPrefix.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}")) {
            // 非法部署配置不得将任务派到不明确的索引位置。
            throw new IllegalStateException("知识索引代次前缀无效");
        }
        return collectionPrefix + "_" + build.replace("-", "");
    }
}
