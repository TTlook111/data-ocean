package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.entity.vo.QueryColumnVO;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.mapper.QueryExplanationMapper;
import com.dataocean.module.query.mapper.QueryExplanationMapper.Explanation;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 对工业能耗和物流结果的说明验证数值符号、实际预算、授权、重试及原快照。
 */
class QueryExplanationsTest {
    /** 模拟权威平台状态和可控的模型响应。 */
    private final String id = UUID.randomUUID().toString();

    private final String command = UUID.randomUUID().toString();
    private final LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    private final QueryRequestMapper queries = mock(QueryRequestMapper.class);
    private final QueryExplanationMapper revisions = mock(QueryExplanationMapper.class);
    private final QueryModels models = mock(QueryModels.class);
    private final AccessScopeService access = mock(AccessScopeService.class);
    private final Request row = mock(Request.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private QueryExplanations service;

    /**
     * 使用原账号、已交付快照及当前规则，真实说明逻辑不依赖数据库定时。
     */
    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(new IamPrincipal(7, 1, "fixture", "能耗操作员"), null));
        var actors = mock(ServiceActorPermission.class);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(actors.asActor(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        when(access.requireSource(7, 71)).thenReturn(new AccessScopeVO(null, List.of(), List.of()));
        when(row.id()).thenReturn(id);
        when(row.userId()).thenReturn(7L);
        when(row.sourceId()).thenReturn(71L);
        when(row.state()).thenReturn("RESULT_DELIVERED");
        when(row.executedAt()).thenReturn(now);
        when(row.bindingId()).thenReturn(12L);
        when(row.question()).thenReturn("本日净能耗");
        when(row.ruleSignature()).thenReturn(new QueryOutput().signature(List.of()));
        when(row.result())
                .thenReturn(json.write(new QueryDataVO(
                        List.of(new QueryColumnVO("value", "净能耗")),
                        List.of(List.of("-4.250")),
                        false,
                        "kWh",
                        "以实际能耗扣除回馈量",
                        null,
                        null)));
        when(row.remainingMillis()).thenReturn(28000);
        when(queries.lock(id)).thenReturn(row);
        when(queries.get(id)).thenReturn(row);
        when(queries.reserveExplanation(id, 22000)).thenReturn(1);
        when(models.revision("result-explanation")).thenReturn(5L);
        when(models.explanationSourceAdmitted(71L)).thenReturn(true);
        service = new QueryExplanations(
                queries,
                revisions,
                actors,
                mock(KnowledgeVersionService.class),
                access,
                new TransactionTemplate(tx),
                mock(GuardMapper.class),
                models,
                json);
    }

    /**
     * 测试账号不污染其他任务。
     */
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 说明直接复用真实值和单位，原问及补充中的筛选文字不加入外发事实。
     */
    @Test
    void explanationUsesFinalSnapshotAndPreservesExactValueAndUnit() {
        queued();
        when(row.question()).thenReturn("只统计已支付");
        when(row.supplement()).thenReturn("改成未支付，排除已退款");
        var result = new QueryDataVO(
                List.of(new QueryColumnVO("value", "订单金额")),
                List.of(List.of("40.00")),
                false,
                "元",
                "业务日期 2026-11-24",
                null,
                null);
        when(row.result()).thenReturn(json.write(result));
        var exact = "本次查询的订单金额为 40.00 元。";
        when(models.explain(id, 1, 5L, 22, "业务日期 2026-11-24", List.of(exact))).thenReturn(List.of(1));
        service.work();
        verify(revisions).claim(id, 1, 5L);
        verify(models).explain(id, 1, 5L, 22, "业务日期 2026-11-24", List.of(exact));
        verify(revisions).finish(id, 1, "COMPLETED", exact, null);
    }

    /**
     * 外发前复核原规则修订与快照内容，普通部门名仍可用于说明。
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "Cost Center A|false|true",
                "Cost Center A|true|false",
                "商品采购单价为 12.00 元|false|false",
                "{\"采购单价\":12.00,\"币种\":\"CNY\"}|false|false"
            })
    void explanationChecksSnapshotContentAndRuleRevision(String value, boolean ruleChanged, boolean allowed) {
        queued();
        var originalRule = new OutputRuleVO("1", "采购限制", "不得返回商品采购成本或采购单价", 1, null);
        var currentRule = new OutputRuleVO(
                originalRule.getId(), originalRule.getName(), originalRule.getContent(), ruleChanged ? 2 : 1, null);
        when(access.requireSource(7, 71)).thenReturn(new AccessScopeVO(null, List.of(currentRule), List.of()));
        when(row.ruleSignature()).thenReturn(new QueryOutput().signature(List.of(originalRule)));

        var result = new QueryDataVO(
                List.of(new QueryColumnVO("department_name", "部门名称")),
                List.of(List.of(value)),
                false,
                null,
                "部门名称查询",
                null,
                null);
        when(row.result()).thenReturn(json.write(result));
        var exact = "本次查询的部门名称为 " + value + "。";
        when(models.explain(id, 1, 5L, 22, result.getScope(), List.of(exact))).thenReturn(List.of(1));

        service.work();

        if (!allowed) {
            // 规则修订已变或快照内容受限时，不占模型预算也不发出快照。
            verify(queries, never()).reserveExplanation(anyString(), anyInt());
            verify(models, never()).explain(anyString(), anyInt(), anyLong(), anyInt(), anyString(), any());
            verify(revisions).finish(id, 1, "FAILED", null, "NOT_AVAILABLE");
        } else {
            // 名称里的 Cost 是查询值，沿用已确认的部门列义。
            verify(revisions).finish(id, 1, "COMPLETED", exact, null);
        }
    }

    /**
     * 负值和小数尾零直接从快照交付。
     */
    @Test
    void negativeValueKeepsItsOriginalPrecision() {
        queued();
        var exact = "本次查询的净能耗为 -4.250 kWh。";
        when(models.explain(id, 1, 5L, 22, "以实际能耗扣除回馈量", List.of(exact))).thenReturn(List.of(1));
        service.work();
        verify(revisions).finish(id, 1, "COMPLETED", exact, null);
    }

    /**
     * 多行说明只交付选中行，业务日期不附带金额单位。
     */
    @Test
    void selectedTrendRowsKeepColumnMeaningAndUnits() {
        queued();
        var result = new QueryDataVO(
                List.of(new QueryColumnVO("date", "业务日期"), new QueryColumnVO("value", "订单金额")),
                List.of(List.of("2026-11-24", "40.00"), List.of("2026-11-25", "60.00")),
                false,
                "元",
                "业务日期 2026-11-24 至 2026-11-25",
                null,
                null);
        when(row.result()).thenReturn(json.write(result));
        var first = "第 1 行，业务日期为 2026-11-24，订单金额为 40.00 元。";
        var second = "第 2 行，业务日期为 2026-11-25，订单金额为 60.00 元。";
        when(models.explain(id, 1, 5L, 22, result.getScope(), List.of(first, second)))
                .thenReturn(List.of(2));
        service.work();
        verify(revisions).finish(id, 1, "COMPLETED", second, null);
    }

    /**
     * 两条合法长事实同时入选时仍交付完整正文，后续能容纳的事实可以补入。
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explanationKeepsWholeFactsWithinTextLimit(boolean includesShorterFact) {
        queued();
        var columns = List.of(
                new QueryColumnVO("summary", "摘要"),
                new QueryColumnVO("details", "情况"),
                new QueryColumnVO("notes", "备注"));
        var first = List.of("甲".repeat(180), "乙".repeat(180), "丙".repeat(180));
        var second = List.of("丁".repeat(180), "戊".repeat(180), "己".repeat(180));
        var shorter = List.of("庚".repeat(140), "辛".repeat(139), "壬".repeat(139));
        var rows = includesShorterFact ? List.of(first, second, shorter) : List.of(first, second);
        var result = new QueryDataVO(columns, rows, false, null, "合成查询", null, null);
        when(row.result()).thenReturn(json.write(result));

        var firstFact = "第 1 行，摘要为 " + first.get(0) + "，情况为 " + first.get(1) + "，备注为 " + first.get(2) + "。";
        var secondFact = "第 2 行，摘要为 " + second.get(0) + "，情况为 " + second.get(1) + "，备注为 " + second.get(2) + "。";
        var shorterFact = "第 3 行，摘要为 " + shorter.get(0) + "，情况为 " + shorter.get(1) + "，备注为 " + shorter.get(2) + "。";
        var facts = includesShorterFact ? List.of(firstFact, secondFact, shorterFact) : List.of(firstFact, secondFact);
        var selected = includesShorterFact ? List.of(2, 1, 3) : List.of(1, 2);
        var expected = includesShorterFact ? secondFact + shorterFact : firstFact;
        assertThat(firstFact.length() + secondFact.length()).isEqualTo(1122);
        assertThat(secondFact.length() + shorterFact.length()).isEqualTo(1000);
        when(models.explain(id, 1, 5L, 22, result.getScope(), facts)).thenReturn(selected);

        service.work();

        verify(queries).reserveExplanation(id, 22000);
        verify(revisions).finish(id, 1, "COMPLETED", expected, null);
    }

    /**
     * 单条事实已超限时，只有仍可完整交付的事实才能进入模型候选。
     */
    @Test
    void explanationOnlyOffersFactsThatFitOnTheirOwn() {
        queued();
        var label = "a".repeat(63);
        var columns = List.of(
                new QueryColumnVO(label + "1", label + "1"),
                new QueryColumnVO(label + "2", label + "2"),
                new QueryColumnVO(label + "3", label + "3"),
                new QueryColumnVO(label + "4", label + "4"));
        var longCell = "甲".repeat(200);
        var result = new QueryDataVO(
                columns,
                List.of(List.of(longCell, longCell, longCell, longCell), List.of("甲", "乙", "丙", "丁")),
                false,
                null,
                "合成查询",
                null,
                null);
        when(row.result()).thenReturn(json.write(result));
        var expected = "第 2 行，" + label + "1为 甲，" + label + "2为 乙，" + label + "3为 丙，" + label + "4为 丁。";
        when(models.explain(id, 1, 5L, 22, result.getScope(), List.of(expected)))
                .thenReturn(List.of(1));

        service.work();

        verify(revisions).finish(id, 1, "COMPLETED", expected, null);
    }

    /**
     * 没有能完整交付的事实时，首次入队、详情、重试和旧队列领取按同一长度限制处理。
     */
    @Test
    void explanationWithoutDeliverableFactsDoesNotUseModelBudget() {
        var label = "a".repeat(63);
        var columns = List.of(
                new QueryColumnVO(label + "1", label + "1"),
                new QueryColumnVO(label + "2", label + "2"),
                new QueryColumnVO(label + "3", label + "3"),
                new QueryColumnVO(label + "4", label + "4"));
        var longCell = "甲".repeat(200);
        var result = new QueryDataVO(
                columns, List.of(List.of(longCell, longCell, longCell, longCell)), false, null, "合成查询", null, null);
        when(row.result()).thenReturn(json.write(result));
        when(revisions.latest(id)).thenReturn(revision("UNAVAILABLE", 1));

        service.initial(row);
        verify(revisions).add(id, 1, id, "UNAVAILABLE");
        var view = service.snapshot(row, result);
        assertThat(view.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(view.getBlockedReason()).isEqualTo("结果中的单条事实超出说明长度限制");
        assertThatThrownBy(() -> service.retry(id, command))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage(view.getBlockedReason());
        verify(revisions, never()).add(eq(id), eq(2), anyString(), anyString());

        queued();
        service.work();
        verify(revisions).finish(id, 1, "UNAVAILABLE", null, "NOT_AVAILABLE");
        verify(queries, never()).reserveExplanation(anyString(), anyInt());
        verify(models, never()).explain(anyString(), anyInt(), anyLong(), anyInt(), anyString(), any());
    }

    /**
     * 不交付越界编号对应的说明。
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2})
    void rejectsReferencesOutsideCurrentSnapshot(int index) {
        queued();
        when(models.explain(eq(id), eq(1), eq(5L), eq(22), eq("以实际能耗扣除回馈量"), any()))
                .thenReturn(List.of(index));
        service.work();
        verify(revisions).finish(id, 1, "FAILED", null, "EXPLANATION_FAILED");
    }

    /**
     * 目录失效、解释额度不足或结果被清理时，领取不发外部解释。
     */
    @ParameterizedTest
    @ValueSource(strings = {"resource", "reserved", "rules", "result", "missing"})
    void unavailableSnapshotDoesNotLeaveQueueWithData(String state) {
        queued();
        switch (state) {
            case "resource" -> when(row.remainingMillis()).thenReturn(1900);
            case "reserved" -> when(queries.reserveExplanation(id, 22000)).thenReturn(0);
            case "rules" -> when(row.ruleSignature()).thenReturn("other");
            case "result" -> when(row.executedAt()).thenReturn(now.minusDays(2));
            case "missing" -> when(queries.lock(id)).thenReturn(null);
            default -> throw new AssertionError(state);
        }
        service.work();
        verify(revisions).finish(id, 1, "resource".equals(state) ? "UNAVAILABLE" : "FAILED", null, "NOT_AVAILABLE");
        verify(models, never()).explain(anyString(), anyInt(), anyLong(), anyInt(), anyString(), any());
    }

    /**
     * 重试只登记新修订，重复命令直接回读，已完成结果不再覆盖。
     */
    @Test
    void explanationRetryIsIdempotentAndBounded() {
        when(revisions.latest(id)).thenReturn(revision("FAILED", 2));
        assertThat(service.retry(id, command).getRevision()).isEqualTo(3);
        verify(revisions).add(id, 3, command, "QUEUED");
        when(revisions.byCommand(id, command)).thenReturn(revision("QUEUED", 3));
        assertThat(service.retry(id, command).getStatus()).isEqualTo("QUEUED");
        when(revisions.byCommand(id, command)).thenReturn(null);
        when(revisions.latest(id)).thenReturn(revision("COMPLETED", 3), revision("FAILED", 4));
        assertThatThrownBy(() -> service.retry(id, command)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.retry(id, command)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.retry("invalid", command)).isInstanceOf(BizIllegalException.class);
        service.initial(row);
        service.stop(id);
        service.expire();
        verify(revisions).add(id, 1, id, "QUEUED");
        verify(revisions).stop(id);
        verify(revisions).expire();
    }

    /**
     * 未准入的历史和新查数都显示不可用，重试在入队前阻断。
     */
    @Test
    void unadmittedSourceShowsUnavailableAndRejectsRetryBeforeEnqueueing() {
        when(models.explanationSourceAdmitted(71L)).thenReturn(false);
        when(revisions.latest(id)).thenReturn(revision("FAILED", 2));

        assertThat(service.view(id, 71L).getStatus()).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> service.retry(id, command))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("当前数据源未启用结果说明");
        verify(revisions, never()).add(anyString(), anyInt(), anyString(), anyString());
        verify(queries, never()).reserveExplanation(anyString(), anyInt());

        service.initial(row);
        verify(revisions).add(id, 1, id, "UNAVAILABLE");

        // 来源重新获准后，原修订可以在额度内重试。
        when(models.explanationSourceAdmitted(71L)).thenReturn(true);
        when(revisions.latest(id)).thenReturn(revision("UNAVAILABLE", 1));
        assertThat(service.view(id, 71L).getStatus()).isEqualTo("NOT_GENERATED");
        var view = service.snapshot(row, json.read(row.result(), QueryDataVO.class));
        assertThat(view.getStatus()).isEqualTo("NOT_GENERATED");
        assertThat(view.getBlockedReason()).isNull();
        assertThat(service.retry(id, command).getStatus()).isEqualTo("QUEUED");
        verify(revisions).add(id, 2, command, "QUEUED");
    }

    /**
     * 首次说明按交付后预算和实际快照判断，两个临界值以内仍可入队。
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"1|1999|UNAVAILABLE|本次说明的可用处理预算已用完", "21|28000|UNAVAILABLE|结果超过 20 行，无法生成说明", "20|2000|QUEUED|"})
    void initialUsesDeliveryBudgetAndResultSize(int rows, int budget, String expected, String reason) {
        var data = dataWithRows(rows);
        when(row.remainingMillis()).thenReturn(budget);
        when(row.result()).thenReturn(json.write(data));
        service.initial(row);
        verify(revisions).add(id, 1, id, expected);

        // 升级前若已有首条任务排队，也直接反映当前限制。
        when(revisions.latest(id)).thenReturn(revision("QUEUED", 1));
        var view = service.snapshot(row, data);
        assertThat(view.getStatus()).isEqualTo(expected);
        assertThat(view.getBlockedReason()).isEqualTo(reason);
    }

    /**
     * 旧命令回放保留原说明修订，当前生成资格由最新修订统一决定。
     */
    @Test
    void replayReturnsOriginalRevisionWithLatestRetryEligibility() {
        when(revisions.byCommand(id, command)).thenReturn(revision("FAILED", 2));
        when(revisions.latest(id)).thenReturn(revision("FAILED", 4));

        var replay = service.retry(id, command);
        assertThat(replay.getRevision()).isEqualTo(2);
        assertThat(replay.getStatus()).isEqualTo("FAILED");
        assertThat(replay.getBlockedReason()).isEqualTo("本次说明的重试次数已用完");
        assertThatThrownBy(() -> service.retry(id, UUID.randomUUID().toString()))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage(replay.getBlockedReason());
        verify(revisions, never()).add(anyString(), anyInt(), anyString(), anyString());
    }

    /**
     * 21 行快照在入队前拦截，旧队列领取也不发模型，20 行仍可生成。
     */
    @Test
    void oversizedResultBlocksNewRevisionAndLegacyQueue() {
        when(revisions.latest(id)).thenReturn(revision("FAILED", 1));
        var aboveLimit = dataWithRows(21);
        when(row.result()).thenReturn(json.write(aboveLimit));
        var view = service.snapshot(row, aboveLimit);
        assertThat(view.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(view.getBlockedReason()).isEqualTo("结果超过 20 行，无法生成说明");
        assertThatThrownBy(() -> service.retry(id, command))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage(view.getBlockedReason());
        verify(revisions, never()).add(anyString(), anyInt(), anyString(), anyString());

        // 升级前遗留的队列也会结束为不可用。
        queued();
        service.work();
        verify(revisions).finish(id, 1, "UNAVAILABLE", null, "NOT_AVAILABLE");
        verify(queries, never()).reserveExplanation(anyString(), anyInt());
        verify(models, never()).explain(anyString(), anyInt(), anyLong(), anyInt(), anyString(), any());

        // 端点边界包含第 20 行。
        when(row.result()).thenReturn(json.write(dataWithRows(20)));
        assertThat(service.retry(id, command).getStatus()).isEqualTo("QUEUED");
        verify(revisions).add(id, 2, command, "QUEUED");
    }

    /**
     * 预算耗尽与次数耗尽使用各自原因，前端详情与接口拒绝保持一致。
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"1999|1|本次说明的可用处理预算已用完", "28000|4|本次说明的重试次数已用完"})
    void exhaustedExplanationShowsExactReasonAndCannotRetry(int budget, int version, String reason) {
        when(row.remainingMillis()).thenReturn(budget);
        when(revisions.latest(id)).thenReturn(revision("FAILED", version));
        var view = service.snapshot(row, json.read(row.result(), QueryDataVO.class));
        assertThat(view.getBlockedReason()).isEqualTo(reason);
        assertThatThrownBy(() -> service.retry(id, command))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage(reason);
        verify(revisions, never()).add(anyString(), anyInt(), anyString(), anyString());
    }

    /**
     * 构造跨行数上限的合成结果。
     */
    private QueryDataVO dataWithRows(int size) {
        var rows = IntStream.rangeClosed(1, size)
                .mapToObj(number -> List.of(Integer.toString(number)))
                .toList();
        return new QueryDataVO(List.of(new QueryColumnVO("value", "净能耗")), rows, false, "kWh", "合成结果", null, null);
    }

    /**
     * 只领取权威状态仍处于排队的修订。
     */
    private void queued() {
        var revision = revision("QUEUED", 1);
        when(revisions.next()).thenReturn(revision);
        when(revisions.lock(id, 1)).thenReturn(revision);
    }

    /**
     * 构造带有固定版本及原请求的内部快照。
     */
    private Explanation revision(String state, int version) {
        return new Explanation(id, version, command, state, null, null, null, now.plusMinutes(1), now, now);
    }
}
