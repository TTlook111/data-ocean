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
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.impl.KnowledgeVersionUse;
import com.dataocean.module.query.mapper.QueryConversationMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.Ask;
import com.dataocean.module.query.service.QueryProtocol.Column;
import com.dataocean.module.query.service.QueryProtocol.Data;
import com.dataocean.module.query.service.QueryProtocol.Evidence;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Hit;
import com.dataocean.module.query.service.QueryProtocol.Index;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;
import com.dataocean.module.query.service.QueryProtocol.RunInput;
import com.dataocean.module.query.service.QuerySql.Compiled;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
    private final Data data = new Data(List.of(new Column("route", "线路")), List.of(List.of("三号线")),
            false, "", "", null, null);
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
    private final KnowledgeVersionUse versions = mock(KnowledgeVersionUse.class);
    private final QueryCorpus corpus = mock(QueryCorpus.class);
    private final QueryModels models = mock(QueryModels.class);
    private final QueryJdbc jdbc = mock(QueryJdbc.class);
    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final Fingerprints fingerprints = mock(Fingerprints.class);
    private final AccessScopeVO grant = new AccessScopeVO(null, List.of(), List.of());
    private QueryService service;
    private Request row;

    /**
     * 测试使用真实服务安全上下文和事务回调。
     */
    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new IamPrincipal(USER, 1L, "fixture", "物流操作员"), null));
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(actors.asActor(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        when(access.requireSource(USER, SOURCE)).thenReturn(grant);
        when(catalog.source(SOURCE, true)).thenReturn(source);
        when(fingerprints.command(anyString(), anyString(), any())).thenReturn(new byte[]{1});
        row = record("RETRIEVING");
        when(requests.get(id)).thenReturn(row);
        when(requests.lock(id)).thenReturn(row);
        service = new QueryService(mock(GuardMapper.class), requests, conversations, mock(UserMapper.class),
                context, explanations, access, actors, permissions, catalog, versions, corpus, models,
                jdbc, new TransactionTemplate(transactions), json, fingerprints);
    }

    /**
     * 验收身份不泄漏到同一线程的其他测试。
     */
    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 新命令开启会话，重放只返回原回执，未知执行不能再派发。
     */
    @Test
    void commandsHonorRequestIdentityAndSource() {
        var first = service.ask(new Ask("72", "  物流状态  ", null, null, null), command);
        verify(conversations).insert(first.id(), USER, SOURCE, "物流状态", first.id());
        when(requests.byCommand(USER, command)).thenReturn(row);
        when(row.commandFingerprint()).thenReturn(new byte[]{1});
        assertThat(service.ask(new Ask("72", "物流状态", null, null, null), command).id()).isEqualTo(id);
        when(row.commandFingerprint()).thenReturn(new byte[]{2});
        assertThatThrownBy(() -> service.ask(new Ask("72", "新问题", null, null, null), command))
                .isInstanceOf(BizIllegalException.class);
        when(requests.hasUnresolved(USER, SOURCE, "物流状态")).thenReturn(true);
        assertThatThrownBy(() -> service.ask(new Ask("72", "物流状态", null, null, null),
                UUID.randomUUID().toString())).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.read("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"))
                .isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.ask(new Ask("72", "  ", null, null, null), command))
                .isInstanceOf(BizIllegalException.class);
    }

    /**
     * 后一轮可沿用当前会话或明确父轮次，错配来源即使同一用户也拒绝。
     */
    @Test
    void continuationRejectsWrongSourceAndKeepsOwnParent() {
        String conversation = row.conversationId();
        var entry = new QueryConversationMapper.Conversation(conversation, USER, SOURCE, "原对话", id, time, time);
        when(conversations.lock(conversation)).thenReturn(entry);
        var accepted = service.ask(new Ask("72", "改看妥投状态", null, conversation, true), command);
        verify(conversations).use(conversation, accepted.id());
        verify(requests).insert(argThat(input -> id.equals(input.previous()) && conversation.equals(input.conversation())));
        assertThatThrownBy(() -> service.ask(new Ask("73", "改看妥投状态", id, conversation, true), command))
                .isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.turns(conversation)).isInstanceOf(BizIllegalException.class);
    }

    /**
     * 初次补充、重放及取消都保持原请求编号，交付后停止动作只影响解释。
     */
    @Test
    void clarificationAndCancellationStayOnOriginalRequest() {
        when(requests.answer(id, command, "三号线")).thenReturn(1, 0);
        assertThat(service.answer(id, command, "三号线").id()).isEqualTo(id);
        assertThatThrownBy(() -> service.answer(id, command, "三号线")).isInstanceOf(BizIllegalException.class);
        when(row.clarificationCommand()).thenReturn(command);
        when(row.supplement()).thenReturn("三号线");
        assertThat(service.answer(id, command, "三号线").id()).isEqualTo(id);
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
        when(explanations.view(id)).thenReturn(new QueryProtocol.Explanation(1, "QUEUED", null));
        assertThat(service.read(id).sql()).isNull();
        assertThat(service.read(id).sql()).isEqualTo("SELECT route");
        assertThat(service.progress(id, USER).terminal()).isFalse();
        assertThat(service.progress(id, USER).explanationText()).isNull();
        assertThatThrownBy(() -> service.progress(id, USER + 1)).isInstanceOf(BizIllegalException.class);
        when(explanations.view(id)).thenReturn(new QueryProtocol.Explanation(1, "COMPLETED", "妥投线路为三号线。"));
        assertThat(service.progress(id, USER).explanationText()).isEqualTo("妥投线路为三号线。");
        when(row.ruleSignature()).thenReturn("revoked");
        assertThatThrownBy(() -> service.read(id)).isInstanceOf(BizIllegalException.class);
        assertThatThrownBy(() -> service.progress(id, USER)).isInstanceOf(BizIllegalException.class);
        when(row.executedAt()).thenReturn(time.minusDays(2));
        assertThat(service.read(id).result()).isNull();
        assertThat(service.progress(id, USER).explanationText()).isNull();
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
        when(requests.deliver(id, 2)).thenReturn(1);
        service.work();
        verify(requests).permit(argThat(permit -> BINDING == permit.binding()
                && permit.reasoning().contains("SELECT route")));
        verify(requests).deliver(id, 2);
        verify(explanations).initial(id);
        service.expire();
        verify(requests).expireResults();
        verify(explanations).expire();
    }

    /**
     * 各规划判定必须转成封闭的任务状态，并且不能触发 JDBC。
     */
    @ParameterizedTest
    @CsvSource({"DENIED,PERMISSION_DENIED", "NO_KNOWLEDGE,KNOWLEDGE_INSUFFICIENT",
        "OUT_OF_SCOPE,OUT_OF_SCOPE", "UNRECOGNIZED,TECHNICAL_FAILURE"})
    void rejectsNonExecutablePlan(String decision, String state) throws SQLException {
        ready();
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(new Plan(decision, null, null, null, null, null, List.of(), List.of()));
        service.work();
        verify(requests).stop(id, 2, state, "NOT_SUBMITTED");
        verify(jdbc, never()).execute(any(), anyString(), any(), any(), any());
    }

    /**
     * 等待用户补充时，在原轮次写入提示，缺失提示不转空等待。
     */
    @Test
    void pausesOnlyWhenClarificationIsPresent() throws SQLException {
        ready();
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any()))
                .thenReturn(new Plan("CLARIFY", "请输入线路名称", null, null, null, null, List.of(), List.of()),
                        new Plan("CLARIFY", " ", null, null, null, null, List.of(), List.of()));
        when(requests.waitForAnswer(id, 2, "请输入线路名称")).thenReturn(1);
        service.work();
        service.work();
        verify(requests).waitForAnswer(id, 2, "请输入线路名称");
        verify(requests).stop(id, 2, "KNOWLEDGE_INSUFFICIENT", "NOT_SUBMITTED");
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
            case "input" -> when(corpus.indexes(eq(SOURCE), anyList(), anyInt())).thenReturn(List.of());
            case "cancel" -> when(row.cancelRequested()).thenReturn(true);
            case "expired" -> when(row.remainingMillis()).thenReturn(100);
            case "moved" -> when(row.runInput()).thenReturn(json.write(new RunInput("stale",
                    new Memory(time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of()), List.of(BINDING), 1, 2, null)));
            default -> throw new AssertionError(mode);
        }
        service.work();
        if ("pending".equals(mode)) {
            verify(requests, never()).stop(anyString(), anyInt(), anyString(), anyString());
        } else {
            String expected = switch (mode) {
                case "input" -> "KNOWLEDGE_INSUFFICIENT";
                case "cancel" -> "USER_CANCELLED";
                case "expired" -> "RESOURCE_EXCEEDED";
                case "moved" -> "PERMISSION_DENIED";
                default -> "TECHNICAL_FAILURE";
            };
            verify(requests).stop(id, 2, expected, "sql".equals(mode) ? "UNKNOWN" : "NOT_SUBMITTED");
        }
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
        when(context.capture(eq(row), anyString())).thenReturn(new Memory(
                time.toInstant(ZoneOffset.UTC).toString(), List.of(), List.of()));
        when(corpus.bindings(SOURCE)).thenReturn(List.of(BINDING));
        when(corpus.indexes(eq(SOURCE), anyList(), anyInt())).thenReturn(List.of(mock(Index.class)));
        var hit = new Hit("6", 0, "digest");
        when(models.retrieve(any(), anyString(), anyList(), anyInt())).thenReturn(List.of(hit));
        var basis = mock(QueryCorpus.Basis.class);
        var binding = mock(Binding.class);
        when(binding.id()).thenReturn(BINDING);
        when(binding.title()).thenReturn("物流口径");
        when(basis.binding()).thenReturn(binding);
        when(basis.source()).thenReturn(source);
        when(basis.labels()).thenReturn(Map.of(label, "线路"));
        when(basis.snippet()).thenReturn(new QueryCorpus.Snippet("6", "", Map.of(), List.of()));
        when(basis.evidence()).thenReturn(new Evidence("物流口径", 1, "4", 0));
        when(corpus.basis(SOURCE, hit)).thenReturn(basis);
        when(models.plan(any(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any())).thenReturn(plan);
        when(models.check(any(), anyString(), anyLong(), anyInt(), anyString(), anyList(), anyList(), any(), any()))
                .thenReturn("ALLOW");
        var usage = new QueryUsage(1, List.of(), List.of(), List.of(), List.of());
        var compiled = new Compiled(
                "SELECT route", List.of(), data.columns(), List.of(label), "", "", false, null, usage);
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
        when(request.reasoning()).thenReturn(json.write(new Reasoning(
                new Evidence("物流口径", 1, "4", 0), "SELECT route", List.of(label), List.of(), plan, 4L)));
    }
}
