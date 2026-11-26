package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.Plan;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 使用同版名称声明验收中英文映射、规格追问和额外条件边界。
 */
class QueryProductNamesTest {

    /**
     * 用户用日常量词和英文名称都可确定同一条已声明的商品。
     */
    @Test
    void findsConfirmedProductWithoutUserSuppliedCode() {
        var evidence = List.of(evidence());
        var chinese = QueryProductNames.resolve(evidence, "前天，1升的原味燕麦奶还有多少箱能卖？");
        var english = QueryProductNames.resolve(
                evidence, "How much 1 L Original Oat Milk could we sell the day before yesterday?");
        assertThat(chinese.confirmed()).isTrue();
        assertThat(chinese.simpleStock()).isTrue();
        assertThat(english.confirmed()).isTrue();
        assertThat(english.simpleStock()).isTrue();
        assertThat(english.code()).isEqualTo(chinese.code());
        assertThat(QueryProductNames.resolve(evidence, "前天原味燕麦奶还有多少箱能卖？").alternative())
                .isEqualTo("250 mL");
        assertThat(QueryProductNames.resolve(evidence, "前天原味燕麦奶还有多少箱能卖？\n补充：一升那款")
                        .simpleStock())
                .isTrue();
    }

    /**
     * 其他规格和口味缺乏可核实映射；加仓库的请求不走简单全仓规划。
     */
    @Test
    void leavesUnconfirmedAndExtraScopeOutsideSimplePlan() {
        var evidence = List.of(evidence());
        assertThat(QueryProductNames.resolve(evidence, "前天250毫升的原味燕麦奶还剩多少箱？").confirmed())
                .isFalse();
        assertThat(QueryProductNames.resolve(evidence, "How much Vanilla Oat Milk was available for sale?"))
                .isNull();
        assertThat(QueryProductNames.resolve(evidence, "前天华东仓的1升原味燕麦奶还有多少箱能卖？").simpleStock())
                .isFalse();
    }

    /**
     * 原味商品的已发布编码不能被猜成香草口味或用数字主键替代。
     */
    @Test
    void refusesSubstitutingAnUnconfirmedFlavor() {
        var code = new Field("product", "code");
        var fixture = evidence();
        var plan = new Plan(
                "READY", null, "1", "SUM", "可售库存", null, List.of(code), List.of(new Condition(code, "CODE-900")));
        var memory = new Memory("2026-11-22T00:00:00Z", List.of(), List.of());
        var sql = new QuerySql();
        sql.checkProductCodes(fixture, plan, "1升原味燕麦奶多少箱", memory);
        assertThatThrownBy(
                        () -> sql.checkProductCodes(fixture, plan, "1 L Vanilla Oat Milk available for sale", memory))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 商品映射只来自测试中的已发布片段和已确认编码列。
     */
    private Basis evidence() {
        var field = new Field("product", "code");
        return new Basis(
                null,
                null,
                0,
                "CODE-900 是 1 L 原味燕麦奶，与 250 mL 规格不同。",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(field, "SKU 编码"));
    }
}
