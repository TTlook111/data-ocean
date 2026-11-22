package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.query.service.QueryProtocol.Field;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 问数首条链路的确定性输出限制与版本回查不依赖模型判断。
 */
class QueryOutputTest {

    /** 实际编译使用的业务输出规则。 */
    private final QueryOutput output = new QueryOutput();

    /** 采购值不能交付，作为业务过滤条件时仍核对实际投影。 */
    @Test
    void usesActualOutputLineage() {
        var rules = List.of(rule(1, "不得返回商品采购成本或采购单价"));
        assertThatThrownBy(() -> output.checkDirectRequest(rules, "请给出商品采购单价"))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("PERMISSION_DENIED");
        assertThatCode(() -> output.checkDirectRequest(rules, "按采购单价筛选，返回商品编码")).doesNotThrowAnyException();
        var cost = new Field("sku", "cost_amount");
        var quantity = new Field("inventory", "remaining");
        var basis = new QueryCorpus.Basis(
                null, null, 0, "", Map.of(), Map.of(), Map.of(), Map.of(cost, "商品进货成本", quantity, "可售数量"));
        assertThatThrownBy(() -> output.checkProjection(rules, basis, List.of(cost)))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("PERMISSION_DENIED");
        assertThatCode(() -> output.checkProjection(rules, basis, List.of(quantity)))
                .doesNotThrowAnyException();
    }

    /** 同一规则的新修订会使之前的结果交付签名失效。 */
    @Test
    void refusesUnknownRulesAndChangesSignatureOnRevision() {
        var old = rule(1, "不得返回商品采购成本或采购单价");
        var replacement = rule(2, old.getContent());
        assertThat(output.signature(List.of(old))).isNotEqualTo(output.signature(List.of(replacement)));
        assertThatThrownBy(() -> output.checkSupported(List.of(rule(3, "仅交付归并并脱敏的数据"))))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("PERMISSION_DENIED");
    }

    /** 模拟当前服务给出的实际规则修订。 */
    private OutputRuleVO rule(int version, String content) {
        return new OutputRuleVO("1", "采购限制", content, version, Instant.EPOCH);
    }
}
