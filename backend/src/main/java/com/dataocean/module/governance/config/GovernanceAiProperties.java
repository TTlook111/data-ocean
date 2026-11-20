package com.dataocean.module.governance.config;

import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 治理模型的启用开关和部署侧资料传输范围。
 */
@Getter
@Setter
@Component
@ConfigurationProperties("dataocean.governance-ai")
public class GovernanceAiProperties {

    /**
     * 是否启用治理模型调用。
     */
    private boolean enabled;

    /**
     * 允许向配置的模型发送资料的数据源 ID。
     */
    private Set<Long> modelSourceIds = Set.of();

    /**
     * 模型关闭或来源不在允许传输的范围内时，返回界面可直接显示的原因。
     */
    public String sourceUnavailableReason(long source) {
        return enabled && modelSourceIds.contains(source) ? null : "当前数据源未配置治理模型传输范围";
    }

    /**
     * 各源允许在治理解释时外发样本的字段限定名，默认不外发业务行。
     */
    private Map<Long, Set<String>> sampleAllowedFields = Map.of();

    /**
     * 各源已核对与采样及模型传输范围相容的输出规则修订，格式为规则编号/版本。
     */
    private Map<Long, Set<String>> sampleAllowedRuleRevisions = Map.of();

    /**
     * 当前所有输出限制均须与采样范围相容，规则改版会使此前配置失效。
     */
    public boolean allowsSampleRules(long source, List<OutputRuleVO> rules) {
        var permitted = sampleAllowedRuleRevisions.getOrDefault(source, Set.of());
        return rules.stream().allMatch(rule -> permitted.contains(rule.getId() + "/" + rule.getVersion()));
    }

    /**
     * 各源可选的渐进读取行数，列表长度同时表示最大尝试次数。
     */
    private Map<Long, List<Integer>> sampleRowLimits = Map.of();

    /**
     * 解析有界的每轮额度；无有效预算时仍可用其他依据起草。
     */
    public List<Integer> sampleLimits(long source) {
        var selected = sampleRowLimits.getOrDefault(source, List.of(4, 8, 12));
        if (selected.isEmpty()
                || selected.size() > 3
                || selected.stream().anyMatch(limit -> limit == null || limit < 1 || limit > 20)
                || selected.stream().mapToInt(Integer::intValue).sum() > 40) {
            // 额度超出可控范围时关闭自动取样，人工操作及资料生成继续可用。
            return List.of();
        }
        return List.copyOf(selected);
    }

    /**
     * 只向已明确配置的当前目录列开放样本传输。
     */
    public boolean allowsSample(long source, ObjectRef object, String field) {
        var table = object.schema() == null || object.schema().isBlank()
                ? object.name()
                : object.schema() + "." + object.name();
        return sampleAllowedFields.getOrDefault(source, Set.of()).contains(table + "." + field);
    }
}
