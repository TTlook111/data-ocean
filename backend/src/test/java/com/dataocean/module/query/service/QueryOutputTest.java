package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.service.QueryProtocol.Field;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * 问数首条链路的确定性输出限制与版本回查不依赖模型判断。
 */
class QueryOutputTest {

    /** 实际编译使用的业务输出规则。 */
    private final QueryOutput output = new QueryOutput();

    /**
     * 单元格 JSON 按应用现有类型化解析方式读取。
     */
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());

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

    /**
     * 采购金额出现在普通文本列时仍执行限制，未配置规则的来源可正常返回。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "商品采购单价为 12.00 元",
                "采购成本：１２．５元",
                "进价十二元",
                "Purchase unit price = USD 12",
                "{\"采购单价\":12.00,\"币种\":\"CNY\"}",
                "[{\"purchaseUnitPrice\":{\"amount\":\"12.00\"}}]",
                "{\"采购单价\":{\"年份\":2026,\"amount\":12.00}}",
                "{\"采购单价\":{\"year\":{\"amount\":12.00}}}",
                "{\"采购单价\":{\"year\":\"人民币 2026\"}}",
                "{\"采购单价\":{\"date\":20261199}}",
                "{\"采购单价\":[{\"year\":2026,\"value\":12.00}]}"
            })
    void blocksExplicitProcurementValuesInTextCells(String text) {
        var data = new QueryDataVO();
        data.setRows(List.of(List.of(text)));
        var rules = List.of(rule(1, "不得返回商品采购成本或采购单价"));

        assertThatThrownBy(() -> output.checkResult(rules, data, json)).isInstanceOf(BizIllegalException.class);
        assertThatCode(() -> output.checkResult(List.of(), data, json)).doesNotThrowAnyException();
    }

    /**
     * 普通名称及日期保留原值，即使位于采购业务说明中也不视为金额。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Cost Center A",
                "采购价格2026年版本，仅登记审批进度。",
                "采购单价：2026-11-26 生效",
                "{\"cost_center\":12,\"采购价格版本\":2026}",
                "{\"采购单价\":null}",
                "{\"采购单价\":{\"year\":2026,\"生效日期\":20261126}}",
                "[{\"purchaseUnitPrice\":[{\"businessYear\":\"2026\",\"effectiveDate\":\"2026-11-26\"}]}]"
            })
    void allowsDatesAndOrdinaryJsonValues(String text) {
        var data = new QueryDataVO();
        data.setRows(List.of(List.of(text)));
        var rules = List.of(rule(1, "不得返回商品采购成本或采购单价"));

        assertThatCode(() -> output.checkResult(rules, data, json)).doesNotThrowAnyException();
    }

    /** 模拟当前服务给出的实际规则修订。 */
    private OutputRuleVO rule(int version, String content) {
        return new OutputRuleVO("1", "采购限制", content, version, Instant.EPOCH);
    }
}
