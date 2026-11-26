package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.entity.dto.AskQueryDTO;
import com.dataocean.module.query.entity.dto.QueryEvidenceSnippetDTO;
import com.dataocean.module.query.entity.vo.QueryColumnVO;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.entity.vo.QueryEvidenceVO;
import com.dataocean.module.query.entity.vo.QueryExplanationVO;
import com.dataocean.module.query.mapper.QueryConversationMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.HistorySummary;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.Index;
import com.dataocean.module.query.service.QueryProtocol.Intent;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;
import com.dataocean.module.query.service.QueryProtocol.RerankDecision;
import com.dataocean.module.query.service.QueryProtocol.RerankOrder;
import com.dataocean.module.query.service.QueryProtocol.RequestedTerm;
import com.dataocean.module.query.service.QueryProtocol.RunInput;
import com.dataocean.module.query.service.QueryProtocol.SelectionReview;
import com.dataocean.module.query.service.QueryProtocol.SummaryDecision;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;
import com.dataocean.module.query.service.QuerySql.Compiled;
import com.dataocean.module.query.service.QueryTableSelection.Scope;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 模拟未必容易从实库复现的失败时序，按真实业务入口核验阶段、权限及请求归属。
 */
class QueryServiceTest {
    /** 固定为业务测试身份，并使测试后续可模拟重试及连接改绑。 */
    private static final long USER = 7L;

    private static final long SOURCE = 72L;
    private static final long BINDING = 6L;
    private final String id = UUID.randomUUID().toString();
    private final String command = UUID.randomUUID().toString();
    private final LocalDateTime time = LocalDateTime.now(ZoneOffset.UTC);
    private final Field label = new Field("deliveries", "route");
    private final QueryDataVO data = new QueryDataVO(
            List.of(new QueryColumnVO("route", "线路")), List.of(List.of("三号线")), false, "", "", null, null);
    private final Plan plan = new Plan("READY", null, "6", "VALUES", null, null, List.of(label), List.of());
    private final Source source = new Source(SOURCE, "物流", "ENABLED", 4L, 3L, 1, false, null, null, null, null);

    /** 保留真实控制逻辑，替换平台数据库及外部系统输入。 */
    private final QueryRequestMapper requests = mock(QueryRequestMapper.class);

    private final QueryConversationMapper conversations = mock(QueryConversationMapper.class);
    private final QueryContext context = mock(QueryContext.class);
    private final QueryExplanations explanations = mock(QueryExplanations.class);
    private final AccessScopeService access = mock(AccessScopeService.class);
    private final ServiceActorPermission actors = mock(ServiceActorPermission.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final GovernanceCatalog catalog = mock(GovernanceCatalog.class);
    private final KnowledgeVersionService versions = mock(KnowledgeVersionService.class);
    private final QueryCorpus corpus = mock(QueryCorpus.class);
    private final QueryModels models = mock(QueryModels.class);
    private final QueryJdbc jdbc = mock(QueryJdbc.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final Fingerprints fingerprints = mock(Fingerprints.class);
    private final AccessScopeVO grant = new AccessScopeVO(null, List.of(), List.of());
    /** 提问和结果读取使用正式接口服务。 */
    private QueryService service;

    /** 队列执行和过期处理使用独立工作者。 */
    private QueryWorker worker;

    private Request row;

    /**
     * 测试使用真实服务安全上下文和事务回调。
     */
    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(new IamPrincipal(USER, 1L, "fixture", "物流操作员"), null));
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(actors.asActor(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        when(access.requireSource(USER, SOURCE)).thenReturn(grant);
        when(catalog.source(SOURCE, true)).thenReturn(source);
        when(fingerprints.command(anyString(), anyString(), any())).thenReturn(new byte[] {1});
        row = record("RETRIEVING");
        when(requests.get(id)).thenReturn(row);
        when(requests.lock(id)).thenReturn(row);
        var guard = mock(GuardMapper.class);
        var users = mock(UserMapper.class);
        var tx = new TransactionTemplate(transactions);
        var runAccess = new QueryRunAccess(requests, actors, access, catalog, versions);
        service = new QueryService(
                guard,
                requests,
                conversations,
                users,
                runAccess,
                models,
                explanations,
                access,
                actors,
                permissions,
                catalog,
                versions,
                tx,
                json,
                fingerprints);
        worker = new QueryWorker(
                guard, requests, users, context, explanations, runAccess, corpus, models, jdbc, tx, json, fingerprints);
    }

    /**
     * 验收身份不泄漏到同一线程的其他测试。
     */
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 数据同步修正可沿用原发布，但复验必须是本次修正后、同一授权下真正交付的轮次。
     */
    @Test
    void sourceReplayAcceptsActualDeliveryOnTheOriginalBinding() {
        delivered(row);
        var originId = UUID.randomUUID().toString();
        var original = record("RESULT_DELIVERED");
        when(original.bindingId()).thenReturn(BINDING);
        when(row.recheckOriginId()).thenReturn(originId);
        when(requests.get(originId)).thenReturn(original);
        String rules = row.ruleSignature();

        assertThat(service.sourceFeedbackReplayMatches(id, originId, USER, SOURCE, time.minusSeconds(1), rules, true))
                .isTrue();
        verify(versions).requireForFeedbackReplay(USER, SOURCE, BINDING);
        assertThat(service.sourceFeedbackReplayMatches(id, originId, USER, SOURCE, time.plusSeconds(1), rules, true))
                .isFalse();
        assertThat(service.sourceFeedbackReplayMatches(id, originId, USER + 1, SOURCE, time, rules, true))
                .isFalse();
        assertThat(service.sourceFeedbackReplayMatches(id, originId, USER, SOURCE, time, "不同输出规则", true))
                .isFalse();
        assertThat(service.feedbackReplayMatches(id, originId, USER, SOURCE, BINDING, time, rules, true))
                .isFalse();
    }

    /**
     * 新命令开启会话，重放只返回原回执，未知执行不能再派发。
     */
    @Test
    void commandsHonorRequestIdentityAndSource() {
        var first = service.ask(new AskQueryDTO("72", "  物流状态  ", null, null, null), command);
        verify(conversations).insert(first.getId(), USER, SOURCE, "物流状态", first.getId());
        when(requests.byCommand(USER, command)).thenReturn(row);
        when(row.commandFingerprint()).thenReturn(new byte[] {1});
        assertThat(service.ask(new AskQueryDTO("72", "物流状态", null, null, null), command)
                        .getId())
                .isEqualTo(id);
        when(row.commandFingerprint()).thenReturn(new byte[] {2});
        assertThatThrownBy(() -> service.ask(new AskQueryDTO("72", "新问题", null, null, null), command))
                .isInstanceOf(BizIllegalException.class);
        when(requests.hasUnresolved(USER, SOURCE, "物流状态")).thenReturn(true);
        assertThatThrownBy(() -> service.ask(
                        new AskQueryDTO("72", "物流状态", null, null, null),
                        UUID.randomUUID().toString()))
                .isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.read("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"))
                .isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.ask(new AskQueryDTO("72", "  ", null, null, null), command))
                .isInstanceOf(BizIllegalException.class);
    }

    /**
     * 必备模型不可用时拒绝入队，原命令的回执仍可以读取。
     */
    @Test
    void checksIntentConfigurationOnlyForNewCommands() {
        var input = new AskQueryDTO("72", "物流状态", null, null, null);
        doThrow(BizIllegalException.state("问题理解模型暂不可用")).when(models).requireIntentReady(SOURCE);

        assertThatThrownBy(() -> service.ask(input, command)).hasMessage("问题理解模型暂不可用");
        verify(requests, never()).insert(any());
        verify(conversations, never()).insert(anyString(), anyLong(), anyLong(), anyString(), anyString());

        when(requests.byCommand(USER, command)).thenReturn(row);
        when(row.commandFingerprint()).thenReturn(new byte[] {1});
        assertThat(service.ask(input, command).getId()).isEqualTo(id);
    }

    /**
     * 后一轮可沿用当前会话或明确父轮次，错配来源即使同一用户也拒绝。
     */
    @Test
    void continuationRejectsWrongSourceAndKeepsOwnParent() {
        String conversation = row.conversationId();
        var entry = new QueryConversationMapper.Conversation(conversation, USER, SOURCE, "原对话", id, time, time);
        when(conversations.lock(conversation)).thenReturn(entry);
        var accepted = service.ask(new AskQueryDTO("72", "改看妥投状态", null, conversation, true), command);
        verify(conversations).use(conversation, accepted.getId());
        verify(requests)
                .insert(argThat(input -> id.equals(input.previous()) && conversation.equals(input.conversation())));
        assertThatThrownBy(() -> service.ask(new AskQueryDTO("73", "改看妥投状态", id, conversation, true), command))
                .isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.turns(conversation)).isInstanceOf(BizIllegalException.class);
    }

    /**
     * 初次补充、重放及取消都保持原请求编号，交付后停止动作只影响解释。
     */
    @Test
    void clarificationAndCancellationStayOnOriginalRequest() {
        when(requests.answer(id, command, "三号线")).thenReturn(1, 0);
        assertThat(service.answer(id, command, "三号线").getId()).isEqualTo(id);
        assertThatThrownBy(() -> service.answer(id, command, "三号线")).isInstanceOf(BizIllegalException.class);
        when(row.clarificationCommand()).thenReturn(command);
        when(row.supplement()).thenReturn("三号线");
        assertThat(service.answer(id, command, "三号线").getId()).isEqualTo(id);
        assertThatThrownBy(() -> service.answer(id, command, "四号线")).isInstanceOf(BizIllegalException.class);
        service.cancel(id);
        verify(requests).cancel(id);
        when(row.state()).thenReturn("RESULT_DELIVERED");
        service.cancel(id);
        verify(explanations).stop(id);
    }

    /**
     * 回读始终核对新授权和结果龄期，SQL 查看权独立于业务数据。
     */
    @Test
    void readsOnlyStillAuthorizedSnapshot() {
        delivered(row);
        when(permissions.effective(USER)).thenReturn(Set.of("query.view"), Set.of("query.view", "query.sql"));
        when(explanations.view(id, SOURCE)).thenReturn(new QueryExplanationVO(1, "QUEUED", null, null));
        assertThat(service.read(id).getSql()).isNull();
        assertThat(service.read(id).getSql()).isEqualTo("SELECT route");
        assertThat(service.progress(id, USER).terminal()).isFalse();
        assertThat(service.progress(id, USER).explanationText()).isNull();
        assertThatThrownBy(() -> service.progress(id, USER + 1)).isInstanceOf(BizIllegalException.class);
        when(explanations.view(id, SOURCE)).thenReturn(new QueryExplanationVO(1, "COMPLETED", "妥投线路为三号线。", null));
        assertThat(service.progress(id, USER).explanationText()).isEqualTo("妥投线路为三号线。");
        when(row.ruleSignature()).thenReturn("revoked");
        assertThatThrownBy(() -> service.read(id)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.progress(id, USER)).isInstanceOf(BizIllegalException.class);
        when(row.executedAt()).thenReturn(time.minusDays(2));
        assertThat(service.read(id).getResult()).isNull();
        assertThat(service.progress(id, USER).explanationText()).isNull();
    }

    /**
     * 历史快照含受限信息时，详情与已生成说明的订阅都不能再交付正文。
     */
    @Test
    void refusesRestrictedLegacyResultsOnReadAndStream() {
        String signature = restrictedResult("{\"采购单价\":12.00,\"币种\":\"CNY\"}");
        delivered(row);
        when(row.ruleSignature()).thenReturn(signature);
        when(explanations.view(id, SOURCE)).thenReturn(new QueryExplanationVO(1, "COMPLETED", "已结束", null));

        assertThatThrownBy(() -> service.read(id)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.progress(id, USER)).isInstanceOf(BizIllegalException.class);
    }

    /**
     * 新结果在入库前拦截，旧队列捕获的结果也不能从恢复分支交付。
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "false|商品采购单价为 12.00 元", "true|商品采购单价为 12.00 元",
        "false|{\"采购单价\":12.00,\"币种\":\"CNY\"}", "true|{\"采购单价\":12.00,\"币种\":\"CNY\"}"
    })
    void rejectsRestrictedResultsBeforeCaptureOrRecoveryDelivery(boolean previouslyCaptured, String cell)
            throws SQLException {
        String signature = restrictedResult(cell);
        ready();
        when(requests.permit(any())).thenReturn(1);
        when(row.executionId()).thenReturn("fixture-execution");
        if (previouslyCaptured) {
            // 模拟应用更新前已经捕获但尚未交付的结果。
            delivered(row);
            when(row.state()).thenReturn("FINALIZING");
            when(row.ruleSignature()).thenReturn(signature);
        }

        worker.work();

        verify(requests).stop(id, 2, "PERMISSION_DENIED", "SUCCEEDED", null, previouslyCaptured ? null : signature);
        verify(requests, never()).capture(anyString(), anyInt(), anyString());
        verify(requests, never()).deliver(anyString(), anyInt());
        verify(explanations, never()).initial(any());
    }

    /**
     * 实际 SQL 只在领取代次和授权有效时执行，捕获之后才允许交付。
     */
    @Test
    void workRegistersOneExecutionBeforeCaptureAndDelivery() throws SQLException {
        ready();
        var captured = record("FINALIZING");
        delivered(captured);
        when(captured.state()).thenReturn("FINALIZING");
        when(requests.permit(any())).thenReturn(1);
        when(requests.capture(eq(id), eq(2), anyString())).thenAnswer(call -> {
            // 模拟 JDBC 返回后写入的实际快照和可交付状态。
            when(requests.get(id)).thenReturn(captured);
            return 1;
        });
        var deliveredRequest = record("RESULT_DELIVERED");
        when(requests.deliver(id, 2)).thenAnswer(call -> {
            // 交付后读到的是已结算预算的请求，而非交付前快照。
            when(requests.get(id)).thenReturn(deliveredRequest);
            return 1;
        });
        worker.work();
        verify(requests)
                .permit(argThat(permit ->
                        BINDING == permit.binding() && permit.reasoning().contains("SELECT route")));
        verify(requests).deliver(id, 2);
        verify(explanations).initial(deliveredRequest);
        worker.expire();
        verify(requests).expireResults();
        verify(explanations).expire();
    }

    /**
     * 新轮次固定问题理解的独立修订，并把理解结果用在第一次知识召回。
     */
    @Test
    void understandsQuestionBeforeRetrievalUsingItsOwnRevision() throws SQLException {
        ready();

        worker.work();

        var calls = inOrder(models);
        calls.verify(models).revision("intent");
        calls.verify(models).understand(any(), eq(31L), anyInt(), eq("物流状态"), any());
        calls.verify(models).retrieve(any(), eq("物流状态\n按已确认线路统计物流状态"), anyList(), anyInt());
        verify(requests)
                .freeze(
                        eq(id),
                        eq(2),
                        argThat(input -> json.read(input, RunInput.class).intentRevision() == 31L));
    }

    /**
     * 模型原样返回短追问时，仍用上轮原问和已确认指标参与检索。
     */
    @Test
    void keepsPriorRetrievalContextWhenIntentLeavesFollowUpUnchanged() throws SQLException {
        ready();
        var prior = new Plan("READY", null, "6", "VALUES", "妥投量", null, List.of(label), List.of());
        var memory = new Memory(
                time.toInstant(ZoneOffset.UTC).toString(),
                List.of(new MemoryTurn(UUID.randomUUID().toString(), "妥投量是多少？", null, prior)),
                List.of());
        when(row.question()).thenReturn("按线路呢？");
        when(context.capture(eq(row), anyString())).thenReturn(memory);
        when(models.understand(any(), eq(31L), anyInt(), eq("按线路呢？"), eq(memory)))
                .thenReturn(new Intent("按线路呢？"));

        worker.work();

        verify(models).retrieve(any(), eq("按线路呢？\n同源上轮：妥投量是多少？\n已确认指标：妥投量"), anyList(), anyInt());
    }

    /**
     * 旧轮次恢复时沿用原始召回输入，以便继续读取之前的步骤回执。
     */
    @Test
    void resumesLegacyRetrievalWithOriginalInput() throws SQLException {
        ready();
        var memory = new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of());
        when(row.runInput())
                .thenReturn(json.write(Map.of(
                        "signature",
                        new QueryOutput().signature(List.of()),
                        "memory",
                        memory,
                        "bindings",
                        List.of(BINDING),
                        "generationRevision",
                        1,
                        "semanticRevision",
                        2)));

        worker.work();

        verify(models, never()).understand(any(), anyLong(), anyInt(), anyString(), any());
        verify(models).retrieve(any(), eq("物流状态"), anyList(), anyInt());
    }

    /** 独立的上周追问只询问缺少的对象和指标，保留已经明确的日期。 */
    @Test
    void asksOnlyForMissingScopeOnStandalonePreviousWeek() throws SQLException {
        ready();
        when(row.question()).thenReturn("上周？");
        String prompt = "上周你想查什么指标？请补充商品或范围。";
        when(requests.waitForAnswer(id, 2, prompt)).thenReturn(1);

        worker.work();

        verify(requests).waitForAnswer(id, 2, prompt);
        verify(models, never()).understand(any(), anyLong(), anyInt(), anyString(), any());
        verify(requests, never()).permit(any());
    }

    /** 可选摘要成功和不可用都在理解之前固定，不因后续恢复更换上下文。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void freezesSelectedHistoryAndReusesItAfterClaim(boolean succeeded) throws SQLException {
        ready();
        var older = new MemoryTurn("old", null, null, plan);
        var raw = new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of(older));
        when(context.capture(eq(row), anyString())).thenReturn(raw);
        when(models.historySummaryRevision()).thenReturn(35L);
        when(requests.freezeSummary(eq(id), eq(2), anyString())).thenReturn(1);
        if (succeeded) {
            // 本轮实际选用旧计划。
            when(models.summarize(any(), eq(35L), anyInt(), anyString(), any()))
                    .thenReturn(new HistorySummary(List.of("old")));
        } else {
            // 尚未核实网络结果时将确定性回退固定在原轮次。
            when(models.summarize(any(), eq(35L), anyInt(), anyString(), any()))
                    .thenThrow(new QueryFailure("RECOVERY_PENDING"));
        }

        worker.work();

        var ordered = inOrder(requests, models);
        ordered.verify(models).summarize(any(), eq(35L), anyInt(), eq("物流状态"), eq(raw));
        ordered.verify(requests).freezeSummary(eq(id), eq(2), argThat(value ->
                json.read(value, SummaryDecision.class).equals(new SummaryDecision(List.of("old"), succeeded))));
        ordered.verify(models).understand(any(), eq(31L), anyInt(), eq("物流状态"), eq(raw));

        when(row.runInput()).thenReturn(json.write(new RunInput(new QueryOutput().signature(List.of()), raw,
                List.of(BINDING), 1, 2, null, null, 31L, 35L, new SummaryDecision(List.of("old"), succeeded),
                null, null, null)));
        worker.work();
        verify(models).summarize(any(), eq(35L), anyInt(), anyString(), any());
    }

    /** 摘要在途撤权禁止回填或继续发出任何后续模型输入。 */
    @Test
    void stopsWhenHistoryPermissionIsRevokedBeforeDelivery() throws SQLException {
        ready();
        var memory = new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(),
                List.of(new MemoryTurn("old", null, null, plan)));
        when(context.capture(eq(row), anyString())).thenReturn(memory);
        when(models.historySummaryRevision()).thenReturn(35L);
        when(models.summarize(any(), eq(35L), anyInt(), anyString(), any())).thenAnswer(call -> {
            // 模拟模型等待期间，实际发起人的数据源授权被撤销。
            when(access.requireSource(USER, SOURCE)).thenThrow(BizIllegalException.denied());
            return new HistorySummary(List.of("old"));
        });

        worker.work();

        verify(requests, never()).freezeSummary(anyString(), anyInt(), anyString());
        verify(models, never()).understand(any(), anyLong(), anyInt(), anyString(), any());
        verify(requests).stop(eq(id), eq(2), eq("PERMISSION_DENIED"), eq("NOT_SUBMITTED"), any(), any());
    }

    /** 重排只读取已获权正文，原阶段的排序或回退在规划前固定。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void freezesRerankOrFallbackBeforePlanningAndReusesItsOrder(boolean applied) throws SQLException {
        ready();
        var hits = rerankCandidates();
        var first = corpus.basis(SOURCE, hits.get(0)).snippet();
        var second = corpus.basis(SOURCE, hits.get(1)).snippet();
        var ranking = when(models.rerank(any(), eq(51L), anyInt(), anyString(), eq(List.of("第一段", "第二段"))));
        if (applied) {
            // 模型仅交换原候选位置。
            ranking.thenReturn(new RerankOrder(List.of(1, 0)));
        } else {
            // 结果未知时原始候选仍可使用，恢复不会重新计算排序。
            ranking.thenThrow(new QueryFailure("RECOVERY_PENDING"));
        }
        var hitsInOrder = applied ? List.of(hits.get(1), hits.get(0)) : hits;
        var snippets = applied ? List.of(second, first) : List.of(first, second);
        var decision = new RerankDecision(hitsInOrder, applied);
        when(requests.freezeRerank(eq(id), eq(2), eq("$.rerank"), anyString())).thenReturn(1);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenThrow(QueryFailure.knowledge());

        worker.work();

        var ordered = inOrder(requests, models);
        ordered.verify(models).rerank(any(), eq(51L), anyInt(), anyString(), eq(List.of("第一段", "第二段")));
        ordered.verify(requests).freezeRerank(eq(id), eq(2), eq("$.rerank"),
                argThat(value -> json.read(value, RerankDecision.class).equals(decision)));
        ordered.verify(models).plan(any(), anyLong(), anyInt(), anyString(), eq(snippets), anyList(), any());
        var input = new RunInput(new QueryOutput().signature(List.of()),
                new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of()), List.of(BINDING),
                1L, 2L, null, null, 31L, 0L, null, 51L, decision, null);
        when(row.runInput()).thenReturn(json.write(input));
        worker.work();
        verify(models).rerank(any(), eq(51L), anyInt(), anyString(), anyList());
    }

    /** 旧版片段校验失败不能送到重排，模型等待期间撤权也不能继续规划。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsUnusableRerankEvidenceBeforeAndAfterItsCall(boolean beforeCall) throws SQLException {
        ready();
        var hits = rerankCandidates();
        if (beforeCall) {
            // 当前绑定已失效的片段不得作为外发资料。
            when(corpus.basis(SOURCE, hits.get(1))).thenThrow(BizIllegalException.denied());
        } else {
            // 外发期间撤销来源访问，收到排序也不得交付。
            when(models.rerank(any(), eq(51L), anyInt(), anyString(), anyList())).thenAnswer(call -> {
                when(access.requireSource(USER, SOURCE)).thenThrow(BizIllegalException.denied());
                return new RerankOrder(List.of(1, 0));
            });
        }

        worker.work();

        if (beforeCall) {
            // 失效候选未触达模型客户端。
            verify(models, never()).rerank(any(), anyLong(), anyInt(), anyString(), anyList());
        }
        verify(requests, never()).freezeRerank(anyString(), anyInt(), anyString(), anyString());
        verify(models, never()).plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any());
    }

    /** 补充后固定新一批召回顺序，不借用补充前的排序。 */
    @Test
    void usesSeparateRerankDecisionAfterClarification() throws SQLException {
        ready();
        var hits = rerankCandidates();
        when(row.supplement()).thenReturn("只看已妥投线路");
        when(row.clarificationCommand()).thenReturn("clarified-command");
        var original = new RerankDecision(List.of(hits.get(1), hits.get(0)), true);
        var input = new RunInput(new QueryOutput().signature(List.of()),
                new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of()), List.of(BINDING),
                1L, 2L, null, null, 31L, 0L, null, 51L, original, null);
        when(row.runInput()).thenReturn(json.write(input));
        when(models.understand(any(), eq(31L), anyInt(), anyString(), any())).thenReturn(new Intent("妥投线路"));
        when(models.rerank(any(), eq(51L), anyInt(), anyString(), anyList()))
                .thenReturn(new RerankOrder(List.of(0, 1)));
        when(requests.freezeRerank(eq(id), eq(2), eq("$.clarifiedRerank"), anyString())).thenReturn(1);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenThrow(QueryFailure.knowledge());

        worker.work();

        verify(models).rerank(argThat(QueryModels.Run::clarified), eq(51L), anyInt(), anyString(), anyList());
        verify(requests).freezeRerank(eq(id), eq(2), eq("$.clarifiedRerank"), argThat(value ->
                json.read(value, RerankDecision.class).equals(new RerankDecision(hits, true))));
    }

    /**
     * 同一发布的首片段缺少依据或引用不支持的对象时，下一片段仍可支持完整计划。
     */
    @ParameterizedTest
    @ValueSource(strings = {"KNOWLEDGE_INSUFFICIENT", "OUT_OF_SCOPE"})
    void selectsCompleteEvidenceEvenWhenRerankPlacesAnUnusableFragmentFirst(String state) throws SQLException {
        ready();
        var hits = rerankCandidates();
        var complete = corpus.basis(SOURCE, hits.get(0));
        var incomplete = corpus.basis(SOURCE, hits.get(1));
        var filter = new Field("deliveries", "status");
        var filtered = new Plan(
                "READY", null, "6", "VALUES", null, null, List.of(label), List.of(new Condition(filter, "已妥投")));
        when(complete.labels()).thenReturn(Map.of(label, "线路", filter, "妥投状态"));
        var binding = complete.binding();
        when(incomplete.binding()).thenReturn(binding);
        when(incomplete.labels()).thenReturn(Map.of(label, "线路"));
        when(jdbc.compile(eq(incomplete), eq(filtered))).thenThrow(new QueryFailure(state));
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(filtered);
        when(models.rerank(any(), eq(51L), anyInt(), anyString(), anyList()))
                .thenReturn(new RerankOrder(List.of(1, 0)));
        when(requests.freezeRerank(eq(id), eq(2), eq("$.rerank"), anyString())).thenReturn(1);
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        var compiled = inOrder(jdbc);
        compiled.verify(jdbc).compile(incomplete, filtered);
        compiled.verify(jdbc).compile(complete, filtered);
        var snippet = complete.snippet();
        verify(models)
                .check(
                        any(),
                        eq("semantic"),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        eq(List.of(snippet)),
                        anyList(),
                        eq(filtered),
                        any());
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 两条同版完整证据指向不同物理表时，任何候选顺序都要先澄清。
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void asksWhichPhysicalTableRegardlessOfRerankOrder(boolean reverse) throws SQLException {
        ready();
        rerankPhysicalTables("biz_b", 21, reverse, false);
        var clarification = "有多个符合条件的同名表。请用模式名.表名指定要查询的表。";
        when(requests.waitForAnswer(id, 2, clarification)).thenReturn(1);

        worker.work();

        verify(requests).waitForAnswer(id, 2, clarification);
        verify(models, never())
                .check(any(), anyString(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any());
        verify(requests, never()).permit(any());
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 历史追问沿用实际执行的物理表与日期字段，重排名次不会更改基准。
     */
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void keepsTheExecutedTableWhenUsingHistoricalDates(boolean reverse, boolean adjacent) throws SQLException {
        ready();
        var bases = rerankPhysicalTables("biz_b", 21, reverse, false);
        var previous = historicalDeliveryDates(bases);
        var question = adjacent ? "那前一天呢？" : "其他条件不变，查线路";
        when(row.question()).thenReturn(question);
        when(context.adjacentDay(question)).thenReturn(adjacent ? -1 : null);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(previous);
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        var snippet = bases.get(0).snippet();
        var day = adjacent ? "2026-11-19" : "2026-11-20";
        var expected = new Plan(
                "READY",
                null,
                "6",
                "VALUES",
                null,
                null,
                List.of(label),
                List.of(previous.filters().get(1)),
                new TimeSelection(previous.filters().get(0).field(), "DAY", day, day, false));
        verify(models)
                .check(
                        any(),
                        eq("semantic"),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        eq(List.of(snippet)),
                        anyList(),
                        eq(expected),
                        any());
        verify(jdbc, never()).compile(eq(bases.get(1)), any());
        verify(requests, never()).waitForAnswer(anyString(), anyInt(), anyString());
    }

    /**
     * 原物理表缺失时，沿用原日期和明确修改日期都停止派发。
     */
    @ParameterizedTest
    @ValueSource(strings = {"adjacent", "clarified", "new-date"})
    void refusesToReplaceTheHistoricalTable(String mode) throws SQLException {
        ready();
        boolean clarified = "clarified".equals(mode);
        var bases = rerankPhysicalTables("biz_b", 21, false, clarified);
        var previous = historicalDeliveryDates(bases);
        var question = "new-date".equals(mode) ? "改查昨天，其他条件不变" : "那前一天呢？";
        when(row.question()).thenReturn(question);
        when(context.adjacentDay("那前一天呢？")).thenReturn(-1);
        when(models.retrieve(any(), anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new Hit("6", 1, "another-digest")));
        if (clarified) {
            // 明确日期后仍需沿用原物理表。
            when(row.clarificationCommand()).thenReturn("clarified-command");
            when(row.clarification()).thenReturn("上一轮无法确定唯一的日期基准。请说明要调整哪个日期条件，或直接给出查询日期。");
            when(row.supplement()).thenReturn("按 2026-11-19 查");
        }
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(previous);

        worker.work();

        verify(requests)
                .stop(
                        id,
                        2,
                        "KNOWLEDGE_INSUFFICIENT",
                        "NOT_SUBMITTED",
                        "INSUFFICIENT_KNOWLEDGE",
                        new QueryOutput().signature(List.of()));
        verify(jdbc, never()).compile(any(), any());
        verify(requests, never()).permit(any());
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 核对派发阶段的 SQL 和绑定值，覆盖保留、修改及澄清后的完整历史计划。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "keep-two",
                "keep-mixed",
                "change-time",
                "change-filter",
                "clarified",
                "change-mixed",
                "change-second",
                "change-wrong",
                "change-wrong-field",
                "change-supplement",
                "change-partial"
            })
    void dispatchesTheCompleteHistoricalConditions(String mode) throws SQLException {
        ready();
        boolean clarified = "clarified".equals(mode) || "change-supplement".equals(mode);
        boolean changesDate = mode.startsWith("change") || clarified;
        boolean mixed = "keep-mixed".equals(mode) || "change-mixed".equals(mode);
        var bases = rerankPhysicalTables("biz_b", 21, true, clarified);
        var previous = historicalDeliveryDates(bases);
        var original = bases.get(0);
        var business = previous.filters().get(0).field();
        var arrival = previous.filters().get(1).field();
        if (!mixed) {
            // 两个日期列均来自上轮真实目录。
            var withTwoDates = deliveryTable(
                    "biz_a",
                    11,
                    new Column(13, 13, business.column(), "date", false, "", 2),
                    new Column(14, 14, arrival.column(), "date", false, "", 3));
            when(original.tables()).thenReturn(withTwoDates);
            historicalExecution(original, previous);
        }
        var newDay = "2026-11-19";
        var question = changesDate ? "业务日改为 " + newDay + "，其他条件不变，查线路" : "其他条件不变，查线路";
        var filters = List.<Condition>of();
        TimeSelection time = null;
        switch (mode) {
            case "keep-mixed" -> {
                // 模型建议的无依据文本值不能覆盖真实筛选。
                filters = List.of(new Condition(arrival, "错误批次"));
            }
            case "change-filter" -> {
                // 日期也可以由规划节点以等值条件给出。
                filters = List.of(new Condition(business, newDay));
            }
            case "clarified" -> {
                // 用户仅选择日期字段，原追问的偏移仍由服务端计算。
                question = "那前一天呢？";
                when(context.adjacentDay(question)).thenReturn(-1);
                when(row.clarificationCommand()).thenReturn("clarified-command");
                when(row.clarification()).thenReturn("上一轮无法确定要调整的日期条件。请说明要调整哪一列，如有新日期请一并给出。");
                when(row.supplement()).thenReturn("按业务日");
            }
            case "change-second" -> {
                // 原业务日以结构化时间保存，修改不同的日期列仍应保留它。
                var oldBusiness = new TimeSelection(business, "DAY", "2026-11-20", "2026-11-20", false);
                var historical = new Plan(
                        "READY",
                        null,
                        "6",
                        "VALUES",
                        null,
                        null,
                        List.of(label),
                        List.of(previous.filters().get(1)),
                        oldBusiness);
                historicalExecution(original, historical);
                question = "入库日改为 " + newDay + "，其他条件不变";
                time = new TimeSelection(arrival, "DAY", newDay, newDay, false);
            }
            case "change-time", "change-mixed" -> {
                // 本轮明确的日期变更可以和已说明的新文本筛选一起应用。
                time = new TimeSelection(business, "DAY", newDay, newDay, false);
                if (mixed) {
                    // 用户确切提供的新值允许替换原值。
                    question = "业务日改为 " + newDay + "，入库批次改为第二批，其他条件不变";
                    filters = List.of(new Condition(arrival, "第二批"));
                }
            }
            case "change-wrong", "change-wrong-field", "change-supplement", "change-partial" -> {
                // 完整的用户日期覆盖错误草案。无法独立确认的日期只交语义节点核对。
                String draft = "change-partial".equals(mode) ? newDay : "2026-11-18";
                var draftField = "change-wrong-field".equals(mode) ? arrival : business;
                time = new TimeSelection(draftField, "DAY", draft, draft, false);
                if ("change-supplement".equals(mode)) {
                    // 后来的明确补充优先于原问题和草案日期。
                    question = "业务日改为 2026-11-18，其他条件不变";
                    when(row.clarificationCommand()).thenReturn("clarified-command");
                    when(row.supplement()).thenReturn("改为 " + newDay);
                } else if ("change-partial".equals(mode)) {
                    // 缺年份的表达继续交独立语义节点复核，不添加已确认日期。
                    question = "业务日改为11月19日，其他条件不变";
                }
            }
            default -> {
                // 保留原条件时模拟规划没有重述任何筛选。
            }
        }
        when(row.question()).thenReturn(question);
        var proposed = new Plan("READY", null, "6", "VALUES", null, null, List.of(label), filters, time);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(proposed);
        var captured = record("FINALIZING");
        delivered(captured);
        when(captured.state()).thenReturn("FINALIZING");
        when(requests.permit(any())).thenReturn(1);
        when(jdbc.execute(eq(source), eq(id), any(), any(), any())).thenAnswer(call -> {
            // 通过实际执行许可回调后返回合成行。
            call.<Runnable>getArgument(4).run();
            return data;
        });
        when(requests.capture(eq(id), eq(2), anyString())).thenAnswer(call -> {
            // 用已捕获状态完成工作者的交付路径。
            when(requests.get(id)).thenReturn(captured);
            return 1;
        });
        when(requests.deliver(id, 2)).thenReturn(1);

        worker.work();

        var execution = ArgumentCaptor.forClass(Compiled.class);
        verify(jdbc).execute(eq(source), eq(id), execution.capture(), any(), any());
        assertThat(execution.getValue().sql())
                .contains("FROM \"biz_a\".\"deliveries\"", "t0.\"business_date\" = ?", "t0.\"arrival_date\" = ?");
        var oldArrival = mixed ? "2026-11-21" : LocalDate.parse("2026-11-21");
        var day = LocalDate.parse(changesDate ? newDay : "2026-11-20");
        if (!changesDate && !mixed) {
            // 普通等值条件按列名排序。
            assertThat(execution.getValue().parameters()).containsExactly(oldArrival, day);
        } else {
            // 业务日以结构化时间传递时，日期参数排在其他筛选前。
            var retained =
                    switch (mode) {
                        case "change-second" -> LocalDate.parse("2026-11-20");
                        case "change-mixed" -> "第二批";
                        default -> oldArrival;
                    };
            assertThat(execution.getValue().parameters()).containsExactly(day, retained);
        }
        verify(requests).permit(any());
        verify(requests).deliver(id, 2);
        var semanticQuestion = ArgumentCaptor.forClass(String.class);
        verify(models)
                .check(
                        any(),
                        eq("semantic"),
                        anyLong(),
                        anyInt(),
                        semanticQuestion.capture(),
                        anyList(),
                        anyList(),
                        any(),
                        any());
        if (mode.startsWith("change-wrong") || "change-supplement".equals(mode)) {
            // 独立审核看到的确认日期必须与实际 SQL 使用的日期一致。
            assertThat(semanticQuestion.getValue())
                    .contains("系统确认的查询业务日期：" + newDay)
                    .doesNotContain("系统确认的查询业务日期：2026-11-18");
        } else if ("change-partial".equals(mode)) {
            // 模型提议的年份缺少用户依据时不做系统背书。
            assertThat(semanticQuestion.getValue()).doesNotContain("系统确认的查询业务日期");
        }
    }

    /**
     * 有多个原日期列且用户未说明修改哪列时，模型不能自行选择。
     */
    @Test
    void clarifiesAnExplicitDateThatDoesNotIdentifyTheHistoricalField() throws SQLException {
        ready();
        var bases = rerankPhysicalTables("biz_b", 21, true, false);
        var previous = historicalDeliveryDates(bases);
        var original = bases.get(0);
        var business = previous.filters().get(0).field();
        var arrival = previous.filters().get(1).field();
        var withTwoDates = deliveryTable(
                "biz_a",
                11,
                new Column(13, 13, business.column(), "date", false, "", 2),
                new Column(14, 14, arrival.column(), "date", false, "", 3));
        when(original.tables()).thenReturn(withTwoDates);
        historicalExecution(original, previous);
        when(row.question()).thenReturn("改成 2026-11-19，其他条件不变");
        var time = new TimeSelection(business, "DAY", "2026-11-19", "2026-11-19", false);
        var proposed = new Plan("READY", null, "6", "VALUES", null, null, List.of(label), List.of(), time);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(proposed);
        var clarification = "上一轮无法确定要调整的日期条件。请说明要调整哪一列，如有新日期请一并给出。";
        when(requests.waitForAnswer(id, 2, clarification)).thenReturn(1);

        worker.work();

        verify(requests).waitForAnswer(id, 2, clarification);
        verify(jdbc, never()).compile(any(), any());
        verify(requests, never()).permit(any());
    }

    /**
     * 原问题和澄清中都可明确指定模式，澄清中的限定名优先。
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void usesTheExplicitPhysicalTableEvenWhenItRanksSecond(boolean clarified) throws SQLException {
        ready();
        var bases = rerankPhysicalTables("biz_b", 21, true, clarified);
        if (clarified) {
            // 补充带双引号的限定名，替换原问题指向的模式。
            when(row.question()).thenReturn("查询 biz_b.deliveries 的线路");
            when(row.clarificationCommand()).thenReturn("clarified-command");
            when(row.supplement()).thenReturn("查\"biz_a\".\"deliveries\"的线路");
        } else {
            // 原问题已经给出明确的模式。
            when(row.question()).thenReturn("查biz_a.deliveries的线路");
        }
        // 让真实工作者走完选片，只核对复核收到的实际证据。
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        var snippet = bases.get(0).snippet();
        verify(models).plan(any(), anyLong(), anyInt(), anyString(), eq(List.of(snippet)), anyList(), any());
        verify(corpus, never()).targetedScope(anyLong(), anyList(), anyList(), anyString(), any());
        verify(models)
                .check(
                        any(),
                        eq("semantic"),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        eq(List.of(snippet)),
                        anyList(),
                        eq(plan),
                        any());
        verify(requests, never()).waitForAnswer(anyString(), anyInt(), anyString());
    }

    /**
     * 两条完整证据实际使用同一个物理表时保留重排名次。
     */
    @Test
    void keepsTheRerankOrderForTheSamePhysicalTable() throws SQLException {
        ready();
        var bases = rerankPhysicalTables("biz_a", 11, true, false);
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        var snippet = bases.get(1).snippet();
        verify(models)
                .check(
                        any(),
                        eq("semantic"),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        eq(List.of(snippet)),
                        anyList(),
                        eq(plan),
                        any());
        verify(requests, never()).waitForAnswer(anyString(), anyInt(), anyString());
    }

    /**
     * 两个有效发布用同一输入但连接或计量口径冲突时，选择哪个发布都不能取得执行许可。
     */
    @ParameterizedTest
    @CsvSource({"6,keys", "7,keys", "6,bridge", "7,bridge", "6,unit", "7,unit", "6,unit-with-guessed-day"})
    void rejectsConflictingResultsAcrossActiveBindings(String selectedBinding, String conflict) throws SQLException {
        ready();
        var candidates = crossBindingCandidates();
        var chosen = candidates.get(0);
        var sibling = candidates.get(1);
        QueryProtocol.TimeSelection time = null;
        if ("unit-with-guessed-day".equals(conflict)) {
            // 只有首选发布有该日期列，实际问题未要求日期。
            var day = new Field("deliveries", "business_date");
            when(chosen.labels()).thenReturn(Map.of(label, "线路", day, "业务日期"));
            when(chosen.tables())
                    .thenReturn(deliveryTable("biz_a", 11, new Column(13, 13, day.column(), "date", false, "", 2)));
            time = new QueryProtocol.TimeSelection(day, "DAY", null, null, null);
        }
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(new Plan(
                        "READY", null, selectedBinding, "VALUES", null, null, List.of(label), List.of(), time));

        var roots = List.of(
                new QueryUsage.ObjectRef("11", 1, "1", List.of()), new QueryUsage.ObjectRef("12", 1, "2", List.of()));
        var left = new ArrayList<>(roots);
        var right = new ArrayList<>(roots);
        if ("bridge".equals(conflict)) {
            // 指标表和筛选表不变，两段连接各自经过不同的中间表。
            left.add(new QueryUsage.ObjectRef("13", 1, "3", List.of()));
            right.add(new QueryUsage.ObjectRef("14", 1, "4", List.of()));
        }
        var region = new Field("region", "id");
        var current = Set.of(Set.of(new Field("deliveries", "region_id"), region));
        var historical = Set.of(Set.of(new Field("deliveries", "historical_region_id"), region));
        when(jdbc.compile(eq(chosen), any())).thenReturn(crossBindingCompiled(left, current, "元"));
        when(jdbc.compile(eq(sibling), any()))
                .thenReturn(crossBindingCompiled(
                        right,
                        "keys".equals(conflict) ? historical : current,
                        conflict.startsWith("unit") ? "万元" : "元"));

        worker.work();

        verify(jdbc)
                .compile(eq(chosen), argThat(candidate -> "6".equals(candidate.binding()) && candidate.time() == null));
        verify(jdbc)
                .compile(
                        eq(sibling), argThat(candidate -> "7".equals(candidate.binding()) && candidate.time() == null));
        verify(requests)
                .stop(
                        id,
                        2,
                        "KNOWLEDGE_INSUFFICIENT",
                        "NOT_SUBMITTED",
                        "INSUFFICIENT_KNOWLEDGE",
                        new QueryOutput().signature(List.of()));
        verify(requests, never()).permit(any());
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 首选发布不适用时，只允许输入物理列一致的唯一可执行发布进入语义核验。
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void permitsFallbackOnlyWithIdenticalPhysicalInputs(boolean sameInput) throws SQLException {
        ready();
        var candidates = crossBindingCandidates();
        var first = candidates.get(0);
        var other = candidates.get(1);
        if (!sameInput) {
            // 同名表的物理身份不同，不能因为首选编译失败就换表。
            when(other.tables()).thenReturn(deliveryTable("biz_b", 21));
        }
        when(jdbc.compile(eq(first), any())).thenThrow(QueryFailure.knowledge());
        when(jdbc.compile(eq(other), any()))
                .thenReturn(
                        crossBindingCompiled(List.of(new QueryUsage.ObjectRef("11", 1, "1", List.of())), Set.of(), ""));
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        if (sameInput) {
            // 真正使用后备发布的绑定及片段继续当前轮次的独立语义检查。
            verify(jdbc).compile(eq(other), argThat(candidate -> "7".equals(candidate.binding())));
            var evidence = List.of(other.snippet());
            verify(models)
                    .check(
                            any(),
                            eq("semantic"),
                            anyLong(),
                            anyInt(),
                            anyString(),
                            eq(evidence),
                            anyList(),
                            argThat(candidate -> "7".equals(candidate.binding())),
                            any());
        } else {
            // 物理输入冲突在编译备用发布前就被挡住。
            verify(jdbc, never()).compile(eq(other), any());
            verify(requests)
                    .stop(
                            id,
                            2,
                            "KNOWLEDGE_INSUFFICIENT",
                            "NOT_SUBMITTED",
                            "INSUFFICIENT_KNOWLEDGE",
                            new QueryOutput().signature(List.of()));
        }
        verify(requests, never()).permit(any());
    }

    /**
     * 首选缺少日期依赖时，后备发布的指标按自己的已确认业务日规则绑定当天。
     */
    @Test
    void resolvesFallbackBusinessDateFromItsOwnPublishedMetric() throws SQLException {
        ready();
        var candidates = crossBindingCandidates();
        var first = candidates.get(0);
        var other = candidates.get(1);
        var amount = new Field("deliveries", "amount");
        var businessDate = new Field("deliveries", "business_date");
        var tables = deliveryTable(
                "biz_a",
                11,
                new Column(13, 13, amount.column(), "int", false, "", 2),
                new Column(14, 14, businessDate.column(), "date", false, "", 3));
        var labels = Map.of(amount, "订单金额", businessDate, "业务日期");
        when(first.tables()).thenReturn(tables);
        when(other.tables()).thenReturn(tables);
        when(first.labels()).thenReturn(labels);
        when(other.labels()).thenReturn(labels);
        when(other.fields()).thenReturn(Map.of("14", businessDate));
        var mapper = JsonMapper.builder().build();
        var incomplete = new GovernanceFactVO(
                new GovernanceTargetVO("11", "METRIC", "20"),
                "30",
                1,
                "11",
                "订单金额",
                mapper.readTree("""
                        {"rule":"SUM(deliveries.amount)",
                         "metricRules":{"dateMode":"BUSINESS_DAY","dateFieldId":"14","fixedFilters":[],"unitMode":"NONE"}}
                        """),
                List.of());
        var daily = new GovernanceFactVO(
                new GovernanceTargetVO("11", "METRIC", "21"),
                "31",
                1,
                "11",
                "订单金额",
                mapper.readTree("""
                        {"rule":"SUM(deliveries.amount)","usage":"按业务日期汇总",
                         "metricRules":{"dateMode":"BUSINESS_DAY","dateFieldId":"14","fixedFilters":[],"unitMode":"NONE"}}
                        """),
                List.of(new GovernanceTargetVO("11", "COLUMN", "14")));
        when(first.facts()).thenReturn(Map.of("METRIC:20", incomplete));
        when(other.facts()).thenReturn(Map.of("METRIC:21", daily));
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(new Plan("READY", null, "6", "SUM", "订单金额", null, List.of(amount), List.of()));
        var today = time.toInstant(ZoneOffset.UTC)
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate();
        when(jdbc.compile(eq(other), any())).thenAnswer(call -> {
            // 读取实际 SQL 编译产物，避免只验证日期参数是否出现在模型计划中。
            var compiled = new QuerySql().compile(other, call.getArgument(1), false, "logistics");
            assertThat(compiled.sql()).contains("t0.\"business_date\" = ?");
            assertThat(compiled.parameters()).containsExactly(today);
            return compiled;
        });
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        verify(jdbc)
                .compile(
                        eq(other),
                        argThat(candidate -> "7".equals(candidate.binding())
                                && candidate.time() != null
                                && candidate.time().field().equals(businessDate)));
        verify(models)
                .check(
                        any(),
                        eq("semantic"),
                        anyLong(),
                        anyInt(),
                        anyString(),
                        anyList(),
                        anyList(),
                        argThat(candidate -> candidate.time() != null
                                && today.toString().equals(candidate.time().start())),
                        any());
    }

    /**
     * 复述的固定条件不掩盖冲突，显式业务值、同值数值和已确认 SKU 都按各自依据核对。
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "varchar|PAID|UNPAID|查华东区的订单金额|true|false",
                "varchar|PAID|UNPAID|查华东区已支付订单的金额|false|true",
                "int|1|1.0|查华东区的订单金额|true|true",
                "bigint|7|7|查华东区的订单金额|true|true"
            })
    void comparesConfirmedConditionsAcrossBindings(
            String type,
            String primaryValue,
            String alternativeValue,
            String question,
            boolean compareSibling,
            boolean accepts)
            throws SQLException {
        ready();
        when(row.question()).thenReturn(question);
        var candidates = crossBindingCandidates();
        var amount = new Field("deliveries", "amount");
        var scope = new Field("deliveries", "category");
        var tables = deliveryTable(
                "biz_a",
                11,
                new Column(13, 13, amount.column(), "int", false, "", 2),
                new Column(14, 14, scope.column(), type, false, "", 3));
        var mapper = JsonMapper.builder().build();
        var column = new GovernanceFactVO(
                new GovernanceTargetVO("11", "COLUMN", "14"),
                "40",
                1,
                "11",
                "支付状态",
                mapper.readTree(
                        """
                        {"valueMappings":[{"code":"PAID","meaning":"已支付"},{"code":"UNPAID","meaning":"未支付"}]}
                        """),
                List.of());
        var fixedValues = List.of(primaryValue, alternativeValue);
        for (int i = 0; i < candidates.size(); i++) {
            // 两份发布共享物理字段，但各自携带确认过的固定值。
            var basis = candidates.get(i);
            var fact = new GovernanceFactVO(
                    new GovernanceTargetVO("11", "METRIC", "20"),
                    "30",
                    1,
                    "11",
                    "订单金额",
                    mapper.readTree(
                            """
                            {"rule":"SUM(deliveries.amount)","metricRules":{"dateMode":"NONE",
                             "fixedFilters":[{"fieldId":"14","value":"%s"}],"unitMode":"FIXED","unit":"元"}}
                            """
                                    .formatted(fixedValues.get(i))),
                    List.of(new GovernanceTargetVO("11", "COLUMN", "14")));
            when(basis.tables()).thenReturn(tables);
            when(basis.labels()).thenReturn(Map.of(amount, "订单金额", scope, "bigint".equals(type) ? "SKU ID" : "支付状态"));
            when(basis.fields()).thenReturn(Map.of("14", scope));
            when(basis.facts()).thenReturn(Map.of("METRIC:20", fact, "COLUMN:14", column));
            when(basis.fact("COLUMN", 14)).thenReturn(column);
        }
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(new Plan(
                        "READY",
                        null,
                        "6",
                        "SUM",
                        "订单金额",
                        null,
                        List.of(amount),
                        List.of(new Condition(scope, primaryValue))));
        when(jdbc.compile(any(), any())).thenAnswer(call -> {
            // 候选按实际 SQL 编译后再走冲突与商品校验。
            return new QuerySql().compile(call.getArgument(0), call.getArgument(1), false, "logistics");
        });
        when(models.check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("DENY");

        worker.work();

        if (compareSibling) {
            // 普通问法的其他发布可独立应用自己的固定条件。
            verify(jdbc).compile(eq(candidates.get(1)), argThat(plan -> plan.filters()
                    .equals(List.of(new Condition(scope, alternativeValue)))));
        } else {
            // 用户明确要求已支付，按已确认值对照保留范围。
            verify(jdbc, never()).compile(eq(candidates.get(1)), any());
        }
        if (accepts) {
            // 通过候选与商品核验后，仍使用常规独立语义复核。
            verify(models)
                    .check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any());
        } else {
            // 不可用首选发布的复述值遮挡影响结果的后备口径冲突。
            verify(models, never())
                    .check(any(), eq("semantic"), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any());
        }
    }

    /**
     * 原话条件独立于模型是否复述，用同一份条件排除不适用的发布并送独立复核。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "only-omit",
                "only-echo",
                "exclude",
                "unlimited",
                "sku",
                "supplement",
                "clipped",
                "collated",
                "same-release",
                "same-release-reversed"
            })
    void selectsUsingGroundedUserConditionsAndIncludesExclusionInReview(String scenario) throws SQLException {
        ready();
        var candidates = crossBindingCandidates();
        var amount = new Field("deliveries", "amount");
        boolean sku = "sku".equals(scenario);
        boolean clipped = "clipped".equals(scenario);
        boolean collated = "collated".equals(scenario);
        boolean negated = "exclude".equals(scenario) || collated;
        boolean amended = "supplement".equals(scenario);
        boolean sameRelease = scenario.startsWith("same-release");
        boolean unlimited = "unlimited".equals(scenario);
        var field = new Field("deliveries", sku ? "sku_code" : "payment_state");
        var firstValue = sku ? "CODE-900" : collated ? "paid" : "PAID";
        var otherValue = sku ? "CODE-500" : unlimited ? "PAID" : "UNPAID";
        var value = sku ? "CODE-500" : "已支付";
        var phrase = sku ? "指定 CODE-500" : negated || clipped ? "不统计已支付" : "只统计已支付";
        var question = unlimited || sameRelease ? "查订单金额" : "查订单金额，" + phrase;
        var terms = unlimited || sameRelease
                ? List.<RequestedTerm>of()
                : List.of(
                        new RequestedTerm(clipped ? "统计已支付" : question, value, null, negated ? "EXCLUDE" : "INCLUDE"));
        var correction = "改为只统计未支付";
        var revised = new RequestedTerm(correction, "未支付", null, "INCLUDE", null, "REPLACE");
        var plannedTerms = amended
                ? List.of(terms.get(0).from("QUESTION"), revised.from("SUPPLEMENT"))
                : terms.stream().map(term -> term.from("QUESTION")).toList();
        var requested = new Condition(field, sku ? "CODE-500" : amended ? "UNPAID" : "PAID", negated ? true : null);
        if (sameRelease) {
            // 两段独立确认的指标共享发布，但片段序号不同，交换召回顺序应给出同一澄清结果。
            var binding = candidates.get(0).binding();
            var other = candidates.get(1);
            var second = new Hit("6", 1, "second-fragment");
            var first = new Hit("6", 0, "digest");
            when(other.binding()).thenReturn(binding);
            when(other.ordinal()).thenReturn(1);
            when(corpus.basis(SOURCE, second)).thenReturn(other);
            when(models.retrieve(any(), anyString(), anyList(), anyInt()))
                    .thenReturn(scenario.endsWith("reversed") ? List.of(second, first) : List.of(first, second));
        }
        var tables = deliveryTable(
                "biz_a",
                11,
                new Column(13, 13, amount.column(), "int", false, "", 2),
                new Column(14, 14, field.column(), "varchar", false, "", 3));
        var mapper = JsonMapper.builder().build();
        for (int i = 0; i < candidates.size(); i++) {
            // 两份候选针对同一物理指标，字段解释从正式确认的映射读取。
            var basis = candidates.get(i);
            var fixedValue = i == 0 ? firstValue : otherValue;
            var fixed = i == 0 && unlimited ? "[]" : "[{\"fieldId\":\"14\",\"value\":\"" + fixedValue + "\"}]";
            var metric = new GovernanceFactVO(
                    new GovernanceTargetVO("11", "METRIC", "20"),
                    "30",
                    1,
                    "11",
                    "订单金额",
                    mapper.readTree(
                            """
                            {"rule":"SUM(deliveries.amount)","metricRules":{"dateMode":"NONE",
                             "fixedFilters":%s,"unitMode":"FIXED","unit":"元"}}
                            """
                                    .formatted(fixed)),
                    List.of(new GovernanceTargetVO("11", "COLUMN", "14")));
            var mapping = sku
                    ? "{}"
                    : """
                    {"valueMappings":[{"code":"PAID","meaning":"已支付"},{"code":"UNPAID","meaning":"未支付"}]}
                    """;
            var column = new GovernanceFactVO(
                    new GovernanceTargetVO("11", "COLUMN", "14"),
                    "40",
                    1,
                    "11",
                    sku ? "SKU 编码" : "支付状态",
                    mapper.readTree(mapping),
                    List.of());
            when(basis.tables()).thenReturn(tables);
            when(basis.labels()).thenReturn(Map.of(amount, "订单金额", field, column.getLabel()));
            when(basis.fields()).thenReturn(Map.of("14", field));
            when(basis.facts()).thenReturn(Map.of("METRIC:20", metric, "COLUMN:14", column));
            when(basis.fact("COLUMN", 14)).thenReturn(column);
            when(basis.text()).thenReturn("");
        }
        // 真实规划有时重复用户的排除条件，实际 SQL 仍只采用独立绑定的方向和编码。
        var filters = "only-omit".equals(scenario) ? List.<Condition>of()
                : "exclude".equals(scenario) ? List.of(new Condition(field, "已支付", true))
                : List.of(new Condition(field, firstValue));
        when(row.question()).thenReturn(question);
        when(models.understand(any(), eq(31L), anyInt(), eq(question), any()))
                .thenReturn(new Intent(question, terms, false));
        if (amended) {
            // 同一轮保留原问题回执，最新范围只从补充节点取得。
            when(row.supplement()).thenReturn(correction);
            when(models.understandSupplement(any(), eq(31L), anyInt(), eq(correction), any()))
                    .thenReturn(new Intent(correction, List.of(revised), false));
        }
        if (collated) {
            // 源端实际比较规则视两个不同大小写的参数为同值。
            when(jdbc.sameTextValue(eq(candidates.get(0)), eq(field), eq("paid"), eq("PAID"), anyInt()))
                    .thenReturn(true);
        }
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), eq(plannedTerms)))
                .thenReturn(new Plan("READY", null, "6", "SUM", "订单金额", null, List.of(amount), filters));
        when(jdbc.compile(any(), any())).thenAnswer(call -> {
            // 检查真实编译器生成的实际绑定，而非只看模型返回形状。
            return new QuerySql().compile(call.getArgument(0), call.getArgument(1), false, "logistics");
        });
        when(models.checkSelection(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any(), any()))
                .thenReturn("DENY");
        when(requests.waitForAnswer(id, 2, "有多个已发布的统计口径，你想查哪个范围？")).thenReturn(1);
        when(requests.waitForAnswer(id, 2, "你想包含或排除哪些业务范围？")).thenReturn(1);

        worker.work();

        if (clipped) {
            // 截掉否定词的片段不满足完整原话核对，编译器不接收其相反口径。
            verify(requests).waitForAnswer(id, 2, "你想包含或排除哪些业务范围？");
            verify(jdbc, never()).compile(any(), any());
        } else if (unlimited || sameRelease) {
            // 模型复述任何发布的条件都不构成用户限定，必须停在选择口径前。
            verify(requests).waitForAnswer(id, 2, "有多个已发布的统计口径，你想查哪个范围？");
            verify(models, never())
                    .checkSelection(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any(), any());
        } else {
            // 成功选路的复核输入必须带方向、确切绑定值和不采用另一候选的原因。
            var review = ArgumentCaptor.forClass(SelectionReview.class);
            var actual = ArgumentCaptor.forClass(Plan.class);
            verify(models)
                    .checkSelection(
                            any(),
                            anyLong(),
                            anyInt(),
                            eq(amended ? question + "\n补充：" + correction : question),
                            anyList(),
                            anyList(),
                            actual.capture(),
                            any(),
                            review.capture());
            var selected = negated || sku || amended ? "7" : "6";
            assertThat(actual.getValue().binding()).isEqualTo(selected);
            assertThat(actual.getValue().filters()).contains(requested);
            assertThat(review.getValue().terms()).isEqualTo(plannedTerms);
            assertThat(review.getValue().conditions()).containsExactly(requested);
            assertThat(review.getValue().excluded()).singleElement().satisfies(reason -> {
                // 复核模型可独立核对另一候选的物理字段和已确认固定口径。
                assertThat(reason.binding()).isEqualTo("6".equals(selected) ? "7" : "6");
                assertThat(reason.reason()).isEqualTo("USER_CONDITION_CONFLICT");
                assertThat(reason.fixedFilters())
                        .containsExactly(new Condition(field, "6".equals(selected) ? otherValue : firstValue));
                assertThat(reason.evidence().snippet().getBinding()).isEqualTo(reason.binding());
                assertThat(reason.evidence().columns())
                        .anyMatch(identity -> identity.field().equals(field));
                assertThat(reason.comparison().requested()).isEqualTo(requested);
                assertThat(reason.comparison().equal()).isEqualTo(negated);
            });
        }
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 同一轮召回两份已获准发布，它们的原始物理输入一致。
     */
    private List<QueryCorpus.Basis> crossBindingCandidates() {
        var first = new Hit("6", 0, "digest");
        var second = new Hit("7", 0, "another-document");
        var chosen = corpus.basis(SOURCE, first);
        var sibling = mock(QueryCorpus.Basis.class);
        var binding = mock(Binding.class);
        when(binding.id()).thenReturn(7L);
        when(binding.title()).thenReturn("另一有效发布");
        when(sibling.binding()).thenReturn(binding);
        when(sibling.labels()).thenReturn(Map.of(label, "线路"));
        when(sibling.source()).thenReturn(source);
        when(sibling.snippet()).thenReturn(new QueryEvidenceSnippetDTO("7", "另一有效发布", Map.of(), List.of()));
        when(chosen.tables()).thenReturn(deliveryTable("biz_a", 11));
        when(sibling.tables()).thenReturn(deliveryTable("biz_a", 11));
        when(corpus.bindings(SOURCE)).thenReturn(List.of(BINDING, 7L));
        when(corpus.basis(SOURCE, second)).thenReturn(sibling);
        when(models.retrieve(any(), anyString(), anyList(), anyInt())).thenReturn(List.of(first, second));
        return List.of(chosen, sibling);
    }

    /**
     * 模拟相同输出及筛选的两个编译产物，保留实际对象和连接键区别。
     */
    private Compiled crossBindingCompiled(List<QueryUsage.ObjectRef> objects, Set<Set<Field>> keys, String unit) {
        var usage = new QueryUsage(1, List.of(), objects, List.of(), List.of());
        return new Compiled(
                "SELECT route", List.of(), data.getColumns(), List.of(label), unit, "", false, null, usage, keys);
    }

    /**
     * 指定表没有可用依据时，即使另一模式的同名表完整也不能派发。
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "查询 biz_a.deliveries 的线路",
                "查biz_a.deliveries的线路",
                "查询unknown_schema.deliveries里的线路",
                "请看\"未召回\".\"deliveries\"的数据"
            })
    void neverSubstitutesAnotherTableForAnUnavailableQualifiedTable(String question) throws SQLException {
        ready();
        var bases = rerankPhysicalTables("biz_b", 21, true, false);
        when(row.question()).thenReturn(question);
        when(jdbc.compile(bases.get(0), plan)).thenThrow(QueryFailure.knowledge());
        when(corpus.targetedScope(eq(SOURCE), eq(List.of(BINDING)), anyList(), eq(question), eq(null)))
                .thenThrow(QueryFailure.knowledge());

        worker.work();

        verify(requests)
                .stop(
                        id,
                        2,
                        "KNOWLEDGE_INSUFFICIENT",
                        "NOT_SUBMITTED",
                        "INSUFFICIENT_KNOWLEDGE",
                        new QueryOutput().signature(List.of()));
        verify(requests, never()).permit(any());
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 表名只由原话和已核验目录决定，即使模型编译到不同名的表也不能派发。
     */
    @ParameterizedTest
    @ValueSource(strings = {"recalled", "missing", "supplement", "related-table"})
    void neverUsesAnotherLogicalTableForAnExplicitlyQualifiedRequest(String scenario) throws SQLException {
        ready();
        boolean clarified = "supplement".equals(scenario);
        var bases = rerankPhysicalTables("biz_b", 21, true, clarified);
        if (!"missing".equals(scenario)) {
            // 正确表在召回结果中，但模型选择另一表的投影，匹配时会排除它。
            var requested = new Field("receipts", "route");
            var object = new ObjectRef("logistics", "biz_a", "receipts", "TABLE", null, null, null);
            var structure = new Structure(object, List.of(), List.of(), List.of(), List.of());
            var observation = new Observation(31, 1, 1L, 31L, null, null, null, structure, null);
            var column = new Column(32, 32, requested.column(), "character varying", false, "", 1);
            var entry = new GovernanceCatalog.Entry(31, 1, false, observation, List.of(column));
            var table = new QueryCorpus.Table(entry, Map.of(requested.column(), "线路"));
            if ("related-table".equals(scenario)) {
                // 即使片段也确认 B 表，实际计划仍必须使用用户指定的 A 表。
                var related = bases.get(1).tables().get(label.table());
                when(bases.get(0).tables()).thenReturn(Map.of(requested.table(), table, label.table(), related));
                when(bases.get(0).labels()).thenReturn(Map.of(requested, "线路", label, "线路"));
            } else {
                // 仅 A 表属于本次规划输入。
                when(bases.get(0).tables()).thenReturn(Map.of(requested.table(), table));
                when(bases.get(0).labels()).thenReturn(Map.of(requested, "线路"));
            }
        }
        if (clarified) {
            // 澄清中的明确表名取代原提问选择。
            when(row.question()).thenReturn("查询 biz_b.deliveries 的线路");
            when(row.clarificationCommand()).thenReturn("clarified-command");
            when(row.supplement()).thenReturn("查biz_a.receipts的线路");
        } else {
            // 准确表或整份目录缺失都不能执行模型选出的另一张表。
            when(row.question()).thenReturn("查 biz_a.receipts 的线路");
        }
        boolean missing = "missing".equals(scenario);
        if (missing) {
            // 只有定向补查仍缺少指定表时才结算知识不足。
            when(corpus.targetedScope(eq(SOURCE), eq(List.of(BINDING)), anyList(), anyString(), eq(null)))
                    .thenThrow(QueryFailure.knowledge());
        }

        worker.work();

        verify(requests)
                .stop(
                        id,
                        2,
                        missing ? "KNOWLEDGE_INSUFFICIENT" : "TECHNICAL_FAILURE",
                        "NOT_SUBMITTED",
                        missing ? "INSUFFICIENT_KNOWLEDGE" : "QUERY_PLAN_TARGET_MISMATCH",
                        new QueryOutput().signature(List.of()));
        if (missing) {
            // 没有依据时不调用规划模型猜测表名。
            verify(models, never()).plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any());
        } else {
            // 验证模型只收到已限定过的依据，而且错误没有导致重复规划。
            var snippet = bases.get(0).snippet();
            verify(models).plan(any(), anyLong(), anyInt(), anyString(), eq(List.of(snippet)), anyList(), any());
        }
        verify(models, never())
                .check(any(), anyString(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any());
        verify(requests, never()).permit(any());
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 首轮单独召回异表或完全空召回时，补查后用原指定表完成正式执行。
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void findsTheRequestedTableBeforePlanningWhenVectorRecallMissesIt(boolean emptyRecall) throws SQLException {
        ready();
        var bases = rerankPhysicalTables("biz_b", 21, true, false);
        var first = bases.get(0);
        var second = bases.get(1);
        List<Hit> hits = emptyRecall ? List.of() : List.of(new Hit("6", 1, "another-digest"));
        List<QueryCorpus.Basis> recalled = emptyRecall ? List.of() : List.of(second);
        var question = "查biz_a.deliveries的线路";
        when(row.question()).thenReturn(question);
        when(models.retrieve(any(), anyString(), anyList(), anyInt())).thenReturn(hits);
        when(corpus.targetedScope(SOURCE, List.of(BINDING), recalled, question, null))
                .thenReturn(new Scope(List.of(first), Set.of("11")));
        var captured = record("FINALIZING");
        delivered(captured);
        when(captured.state()).thenReturn("FINALIZING");
        when(requests.permit(any())).thenReturn(1);
        when(jdbc.execute(eq(source), eq(id), any(), any(), any())).thenAnswer(call -> {
            // 走真实许可回调，返回合成业务行。
            call.<Runnable>getArgument(4).run();
            return data;
        });
        when(requests.capture(eq(id), eq(2), anyString())).thenAnswer(call -> {
            // 按已捕获状态校验最终交付。
            when(requests.get(id)).thenReturn(captured);
            return 1;
        });
        when(requests.deliver(id, 2)).thenReturn(1);

        worker.work();

        var snippet = first.snippet();
        var order = inOrder(models, corpus, requests, jdbc);
        order.verify(models).retrieve(any(), anyString(), anyList(), anyInt());
        order.verify(corpus).targetedScope(SOURCE, List.of(BINDING), recalled, question, null);
        order.verify(models).plan(any(), anyLong(), anyInt(), anyString(), eq(List.of(snippet)), anyList(), any());
        var execution = ArgumentCaptor.forClass(Compiled.class);
        order.verify(jdbc).execute(eq(source), eq(id), execution.capture(), any(), any());
        assertThat(execution.getValue().sql()).contains("FROM \"biz_a\".\"deliveries\"");
        verify(models, never()).rerank(any(), anyLong(), anyInt(), anyString(), anyList());
        verify(requests).permit(any());
        verify(requests).deliver(id, 2);
    }

    /**
     * 使用同一发布的两份目录引用，通过实际 SQL 编译器确认各自使用的物理对象。
     */
    private List<QueryCorpus.Basis> rerankPhysicalTables(
            String secondSchema, long secondId, boolean reverse, boolean clarified) {
        var hits = rerankCandidates();
        var first = corpus.basis(SOURCE, hits.get(0));
        var second = corpus.basis(SOURCE, hits.get(1));
        var binding = first.binding();
        when(second.binding()).thenReturn(binding);
        when(second.source()).thenReturn(source);
        when(second.labels()).thenReturn(Map.of(label, "线路"));
        when(first.tables()).thenReturn(deliveryTable("biz_a", 11));
        when(second.tables()).thenReturn(deliveryTable(secondSchema, secondId));
        var compiler = new QuerySql();
        when(jdbc.compile(eq(first), any()))
                .thenAnswer(call -> compiler.compile(first, call.getArgument(1), false, "logistics"));
        when(jdbc.compile(eq(second), any()))
                .thenAnswer(call -> compiler.compile(second, call.getArgument(1), false, "logistics"));
        when(models.rerank(any(), eq(51L), anyInt(), anyString(), anyList()))
                .thenReturn(new RerankOrder(reverse ? List.of(1, 0) : List.of(0, 1)));
        when(requests.freezeRerank(eq(id), eq(2), eq(clarified ? "$.clarifiedRerank" : "$.rerank"), anyString()))
                .thenReturn(1);
        return List.of(first, second);
    }

    /**
     * 创建带线路字段的物理表，根据场景补充目录中的其他列。
     */
    private Map<String, QueryCorpus.Table> deliveryTable(String schema, long id, Column... extraColumns) {
        var ref = new ObjectRef("logistics", schema, "deliveries", "TABLE", null, null, null);
        var structure = new Structure(ref, List.of(), List.of(), List.of(), List.of());
        var observation = new Observation(id, 1, 1L, id, null, null, null, structure, null);
        var columns =
                new ArrayList<>(List.of(new Column(id + 1, id + 1, label.column(), "character varying", false, "", 1)));
        columns.addAll(List.of(extraColumns));
        var entry = new GovernanceCatalog.Entry(id, 1, false, observation, columns);
        return Map.of(label.table(), new QueryCorpus.Table(entry, Map.of(label.column(), "线路")));
    }

    /**
     * 模拟上轮通过等值筛选记录日期的计划，同名表的两列在目录中具有不同原生类型。
     */
    private Plan historicalDeliveryDates(List<QueryCorpus.Basis> bases) {
        var business = new Field(label.table(), "business_date");
        var arrival = new Field(label.table(), "arrival_date");
        var labels = Map.of(label, "线路", business, "业务日", arrival, "入库日");
        when(bases.get(0).labels()).thenReturn(labels);
        when(bases.get(1).labels()).thenReturn(labels);
        var firstTable = deliveryTable(
                "biz_a",
                11,
                new Column(13, 13, business.column(), "date", false, "", 2),
                new Column(14, 14, arrival.column(), "character varying", false, "", 3));
        var secondTable = deliveryTable(
                "biz_b",
                21,
                new Column(23, 23, business.column(), "character varying", false, "", 2),
                new Column(24, 24, arrival.column(), "date", false, "", 3));
        when(bases.get(0).tables()).thenReturn(firstTable);
        when(bases.get(1).tables()).thenReturn(secondTable);
        var previous = new Plan(
                "READY",
                null,
                "6",
                "VALUES",
                null,
                null,
                List.of(label),
                List.of(new Condition(business, "2026-11-20"), new Condition(arrival, "2026-11-21")));
        historicalExecution(bases.get(0), previous);
        return previous;
    }

    /**
     * 历史计划与执行血缘由相同目录和实际编译器生成。
     */
    private void historicalExecution(QueryCorpus.Basis original, Plan previous) {
        var turn = new MemoryTurn("old-with-typed-date", "查询线路", null, previous);
        var memory = new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(turn), List.of());
        var usage =
                new QuerySql().compile(original, previous, false, "logistics").usage();
        when(context.capture(eq(row), anyString())).thenReturn(memory);
        when(context.executionUsage(eq(row), anyString(), eq(turn))).thenReturn(usage);
    }

    /**
     * 双日期历史在首个片段缺一列时仍应澄清，候选顺序不能决定要调整的日期。
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void clarifiesAmbiguousHistoricalDateEvenWithAnIncompleteFragment(boolean partialFirst) throws SQLException {
        ready();
        var hits = rerankCandidates();
        var complete = corpus.basis(SOURCE, hits.get(0));
        var partial = corpus.basis(SOURCE, hits.get(1));
        var businessDate = new Field("deliveries", "business_date");
        var arrivalDate = new Field("deliveries", "arrival_date");
        var tables = deliveryTable(
                "biz_a",
                11,
                new Column(13, 13, businessDate.column(), "date", false, "", 2),
                new Column(14, 14, arrivalDate.column(), "date", false, "", 3));
        var binding = complete.binding();
        when(partial.binding()).thenReturn(binding);
        when(complete.tables()).thenReturn(tables);
        when(partial.tables()).thenReturn(tables);
        when(complete.labels()).thenReturn(Map.of(label, "线路", businessDate, "业务日", arrivalDate, "入库日"));
        when(partial.labels()).thenReturn(Map.of(label, "线路", businessDate, "业务日"));
        var oldPlan = new Plan(
                "READY",
                null,
                "6",
                "VALUES",
                null,
                null,
                List.of(label),
                List.of(new Condition(businessDate, "2026-11-20"), new Condition(arrivalDate, "2026-11-21")));
        historicalExecution(complete, oldPlan);
        var question = "那前一天呢？";
        var clarification = "上一轮无法确定要调整的日期条件。请说明要调整哪一列，如有新日期请一并给出。";
        when(row.question()).thenReturn(question);
        when(context.adjacentDay(question)).thenReturn(-1);
        var order = partialFirst ? List.of(1, 0) : List.of(0, 1);
        when(models.rerank(any(), eq(51L), anyInt(), anyString(), anyList())).thenReturn(new RerankOrder(order));
        when(requests.freezeRerank(eq(id), eq(2), eq("$.rerank"), anyString())).thenReturn(1);
        when(requests.waitForAnswer(id, 2, clarification)).thenReturn(1);

        worker.work();

        verify(requests).waitForAnswer(id, 2, clarification);
        verify(jdbc, never()).compile(any(), any());
        verify(requests, never()).permit(any());
    }

    /** 使用同源两段已批准正文替换默认单条召回。 */
    private List<Hit> rerankCandidates() {
        var first = new Hit("6", 0, "digest");
        var second = new Hit("6", 1, "another-digest");
        var initial = corpus.basis(SOURCE, first);
        var alternate = mock(QueryCorpus.Basis.class);
        when(initial.text()).thenReturn("第一段");
        when(alternate.text()).thenReturn("第二段");
        when(alternate.snippet()).thenReturn(new QueryEvidenceSnippetDTO("6", "第二段", Map.of(), List.of()));
        when(corpus.basis(SOURCE, second)).thenReturn(alternate);
        var hits = List.of(first, second);
        when(models.retrieve(any(), anyString(), anyList(), anyInt())).thenReturn(hits);
        when(models.rerankRevision()).thenReturn(51L);
        return hits;
    }

    /**
     * 新轮次缺少问题理解模型时停止，避免跳过该步骤或借用其他模型。
     */
    @Test
    void stopsBeforeRetrievalWhenIntentModelIsUnassigned() throws SQLException {
        ready();
        when(models.revision("intent")).thenThrow(QueryFailure.modelUnavailable());

        worker.work();

        verify(models, never()).understand(any(), anyLong(), anyInt(), anyString(), any());
        verify(models, never()).retrieve(any(), anyString(), anyList(), anyInt());
        verify(requests, never()).permit(any());
    }

    /**
     * 各规划判定必须转成封闭的任务状态，并且不能触发 JDBC。
     */
    @ParameterizedTest
    @CsvSource({
        "DENIED,PERMISSION_DENIED",
        "NO_KNOWLEDGE,KNOWLEDGE_INSUFFICIENT",
        "OUT_OF_SCOPE,OUT_OF_SCOPE",
        "UNRECOGNIZED,TECHNICAL_FAILURE"
    })
    void rejectsNonExecutablePlan(String decision, String state) throws SQLException {
        ready();
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(new Plan(decision, null, null, null, null, null, List.of(), List.of()));
        worker.work();
        verify(requests).stop(id, 2, state, "NOT_SUBMITTED", diagnostic(state), new QueryOutput().signature(List.of()));
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 等待用户补充时，在原轮次写入提示，缺失提示不转空等待。
     */
    @Test
    void pausesOnlyWhenClarificationIsPresent() throws SQLException {
        ready();
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(
                        new Plan("CLARIFY", "请输入线路名称", null, null, null, null, List.of(), List.of()),
                        new Plan("CLARIFY", " ", null, null, null, null, List.of(), List.of()));
        when(requests.waitForAnswer(id, 2, "请输入线路名称")).thenReturn(1);
        worker.work();
        worker.work();
        verify(requests).waitForAnswer(id, 2, "请输入线路名称");
        verify(requests)
                .stop(
                        id,
                        2,
                        "KNOWLEDGE_INSUFFICIENT",
                        "NOT_SUBMITTED",
                        "INSUFFICIENT_KNOWLEDGE",
                        new QueryOutput().signature(List.of()));
    }

    /**
     * 回执未知、SQL 响应未知和模型坏输入保留不同状态。
     */
    @ParameterizedTest
    @ValueSource(strings = {"sql", "runtime", "pending", "input", "cancel", "expired", "moved"})
    void keepsRecoveryAndSourceFailureBoundaries(String mode) throws SQLException {
        ready();
        switch (mode) {
            case "sql" -> {
                when(row.executionId()).thenReturn("known");
                when(jdbc.execute(any(), anyString(), any(), any(), any())).thenThrow(new SQLException("lost"));
            }
            case "runtime" -> when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                    .thenThrow(new IllegalStateException("internal"));
            case "pending" -> when(models.retrieve(any(), anyString(), anyList(), anyInt()))
                    .thenThrow(new QueryFailure("RECOVERY_PENDING"));
            case "input" -> when(corpus.indexes(eq(SOURCE), anyList(), anyInt()))
                    .thenReturn(List.of());
            case "cancel" -> when(row.cancelRequested()).thenReturn(true);
            case "expired" -> when(row.remainingMillis()).thenReturn(100);
            case "moved" -> when(row.runInput())
                    .thenReturn(json.write(new RunInput(
                            "stale",
                            new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of()),
                            List.of(BINDING),
                            1,
                            2,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)));
            default -> throw new AssertionError(mode);
        }
        worker.work();
        if ("pending".equals(mode)) {
            verify(requests, never()).stop(anyString(), anyInt(), anyString(), anyString(), any(), any());
        } else {
            String expected =
                    switch (mode) {
                        case "input" -> "KNOWLEDGE_INSUFFICIENT";
                        case "cancel" -> "USER_CANCELLED";
                        case "expired" -> "RESOURCE_EXCEEDED";
                        case "moved" -> "PERMISSION_DENIED";
                        default -> "TECHNICAL_FAILURE";
                    };
            verify(requests)
                    .stop(
                            id,
                            2,
                            expected,
                            "sql".equals(mode) ? "UNKNOWN" : "NOT_SUBMITTED",
                            "sql".equals(mode) ? "SOURCE_EXECUTION_UNCERTAIN" : diagnostic(expected),
                            "cancel".equals(mode) ? null : new QueryOutput().signature(List.of()));
        }
    }

    /** 没有生效知识保存可归组的确切事实，模型不会被用来猜测根因。 */
    @Test
    void absentPublishedKnowledgeCreatesSourceLevelDiagnostic() throws SQLException {
        ready();
        when(corpus.bindings(SOURCE)).thenReturn(List.of());
        worker.work();
        verify(requests)
                .stop(
                        id,
                        2,
                        "KNOWLEDGE_INSUFFICIENT",
                        "NOT_SUBMITTED",
                        "NO_PUBLISHED_KNOWLEDGE",
                        new QueryOutput().signature(List.of()));
        verify(models, never()).retrieve(any(), anyString(), anyList(), anyInt());
    }

    /** 校验自动事项的允许范围，其他终态保持空诊断。 */
    private String diagnostic(String state) {
        return switch (state) {
            case "KNOWLEDGE_INSUFFICIENT" -> "INSUFFICIENT_KNOWLEDGE";
            case "TECHNICAL_FAILURE" -> "QUERY_RUNTIME_FAILURE";
            default -> null;
        };
    }

    /**
     * 已领取请求的同版来源、构造计划、合法审核和 JDBC 结果复用同一夹具。
     */
    private void ready() throws SQLException {
        when(context.adjacentDay(anyString())).thenReturn(null);
        when(requests.next()).thenReturn(row);
        when(requests.claim(id, 2)).thenReturn(1);
        when(requests.freeze(eq(id), eq(2), anyString())).thenReturn(1);
        when(requests.stage(eq(id), eq(2), anyString())).thenReturn(1);
        when(context.capture(eq(row), anyString()))
                .thenReturn(new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of()));
        when(corpus.bindings(SOURCE)).thenReturn(List.of(BINDING));
        when(corpus.indexes(eq(SOURCE), anyList(), anyInt())).thenReturn(List.of(mock(Index.class)));
        when(models.revision("intent")).thenReturn(31L);
        when(models.understand(any(), eq(31L), anyInt(), anyString(), any())).thenReturn(new Intent("按已确认线路统计物流状态"));
        when(models.understandSupplement(any(), eq(31L), anyInt(), anyString(), any()))
                .thenReturn(new Intent("按已确认线路统计物流状态"));
        var hit = new Hit("6", 0, "digest");
        when(models.retrieve(any(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));
        var basis = mock(QueryCorpus.Basis.class);
        var binding = mock(Binding.class);
        when(binding.id()).thenReturn(BINDING);
        when(binding.title()).thenReturn("物流口径");
        when(basis.binding()).thenReturn(binding);
        when(basis.source()).thenReturn(source);
        when(basis.labels()).thenReturn(Map.of(label, "线路"));
        when(basis.snippet()).thenReturn(new QueryEvidenceSnippetDTO("6", "", Map.of(), List.of()));
        when(basis.evidence()).thenReturn(new QueryEvidenceVO("物流口径", 1, "4", 0));
        when(corpus.basis(SOURCE, hit)).thenReturn(basis);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(plan);
        when(models.check(any(), anyString(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("ALLOW");
        var usage = new QueryUsage(1, List.of(), List.of(), List.of(), List.of());
        var compiled = new Compiled(
                "SELECT route", List.of(), data.getColumns(), List.of(label), "", "", false, null, usage, Set.of());
        when(jdbc.compile(eq(basis), any())).thenReturn(compiled);
        when(jdbc.execute(eq(source), eq(id), eq(compiled), any(), any())).thenAnswer(call -> {
            assertThat(call.<IntSupplier>getArgument(3).getAsInt()).isPositive();
            call.<Runnable>getArgument(4).run();
            return data;
        });
    }

    /**
     * 权威请求保留当前代次，并设置有效租约和剩余预算。
     */
    private Request record(String state) {
        var request = mock(Request.class);
        when(request.id()).thenReturn(id);
        when(request.userId()).thenReturn(USER);
        when(request.sourceId()).thenReturn(SOURCE);
        when(request.authenticationVersion()).thenReturn(1L);
        when(request.conversationId()).thenReturn(UUID.randomUUID().toString());
        when(request.question()).thenReturn("物流状态");
        when(request.state()).thenReturn(state);
        when(request.executionState()).thenReturn("NOT_SUBMITTED");
        when(request.runVersion()).thenReturn(2);
        when(request.remainingMillis()).thenReturn(120000);
        when(request.createdAt()).thenReturn(time);
        when(request.updatedAt()).thenReturn(time);
        when(request.processingStartedAt()).thenReturn(time);
        when(request.leaseUntil()).thenReturn(time.plusMinutes(2));
        return request;
    }

    /**
     * 回读或捕获恢复使用经过真实 JSON 序列化的结果和执行依据。
     */
    private void delivered(Request request) {
        when(request.state()).thenReturn("RESULT_DELIVERED");
        when(request.executionState()).thenReturn("SUCCEEDED");
        when(request.executedAt()).thenReturn(time);
        when(request.bindingId()).thenReturn(BINDING);
        when(request.ruleSignature()).thenReturn(new QueryOutput().signature(List.<OutputRuleVO>of()));
        when(request.result()).thenReturn(json.write(data));
        when(request.reasoning())
                .thenReturn(json.write(new Reasoning(
                        new QueryEvidenceVO("物流口径", 1, "4", 0), "SELECT route", List.of(label), List.of(), plan, 4L)));
    }

    /**
     * 普通投影中混入实际受限数值，用与当前授权一致的签名进行复验。
     */
    private String restrictedResult(String cell) {
        var rules = List.of(new OutputRuleVO("1", "采购限制", "不得返回商品采购成本或采购单价", 1, null));
        grant.setRules(rules);
        data.setRows(List.of(List.of(cell)));
        return new QueryOutput().signature(rules);
    }
}
