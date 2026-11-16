package com.dataocean.module.query.scheduler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.module.query.entity.po.Conversation;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.ConversationContextSummaryMapper;
import com.dataocean.module.query.mapper.ConversationMapper;
import com.dataocean.module.query.mapper.ConversationMessageMapper;
import com.dataocean.module.query.mapper.QueryAttemptMapper;
import com.dataocean.module.query.mapper.QueryModelCallMapper;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 用 Mock 和独立内存 H2 验证清理保护及候选批次，不访问项目数据库或执行迁移。 */
@ExtendWith(MockitoExtension.class)
class ConversationRetentionSchedulerTest {
    @Mock private ConversationMapper conversations;
    @Mock private ConversationMessageMapper messages;
    @Mock private ConversationContextSummaryMapper summaries;
    @Mock private QueryTaskMapper tasks;
    @Mock private QueryAttemptMapper attempts;
    @Mock private QueryModelCallMapper modelCalls;

    private ConversationRetentionScheduler scheduler;

    @BeforeAll
    static void initTableMetadata() {
        // 初始化条件查询使用的字段名，不建立 SQL 会话。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QueryTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Conversation.class);
    }

    @BeforeEach
    void setUp() {
        // 每例自行提供候选，避免默认候选桩干扰 SQL 批次测试。
        scheduler = new ConversationRetentionScheduler(conversations, messages, summaries, tasks, attempts, modelCalls);
    }

    @Test
    void pendingCancellationAppearingAfterCandidateSelectionKeepsTheConversation() {
        // 候选选出后才出现 PENDING 时，锁后复核仍须保留会话，不能只依赖候选过滤。
        when(conversations.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(Conversation.builder().id(42L).status("ARCHIVED").build()));
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(QueryTask.builder()
                .taskId("pending-task").cancellationDeliveryStatus("PENDING").build()));

        scheduler.purgeExpiredConversations();

        verifyNoInteractions(messages, summaries, attempts, modelCalls);
        verify(tasks, never()).delete(any(LambdaQueryWrapper.class));
        verify(conversations, never()).deleteById(42L);
        ArgumentCaptor<LambdaQueryWrapper<QueryTask>> selection = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tasks).selectList(selection.capture());
        assertThat(selection.getValue().getSqlSegment()).contains("FOR UPDATE");
    }

    @Test
    void acceptedCancellationDoesNotExtendTheExistingHistoryRetention() {
        // 已收到接收回执的记录继续沿用原保留期，避免把所有取消会话永久保留。
        when(conversations.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(Conversation.builder().id(42L).status("ARCHIVED").build()));
        when(tasks.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(QueryTask.builder()
                .taskId("accepted-task").cancellationDeliveryStatus("ACCEPTED").build()));

        scheduler.purgeExpiredConversations();

        verify(tasks).delete(any(LambdaQueryWrapper.class));
        verify(attempts).delete(any(LambdaQueryWrapper.class));
        verify(modelCalls).delete(any(LambdaQueryWrapper.class));
        verify(conversations).deleteById(42L);
    }

    @Test
    void longPendingConversationsCannotFillTheCandidateLimitForever() throws Exception {
        // 取实际生产 Wrapper，使用 MyBatis 参数绑定执行它，避免在测试中另写一套候选规则。
        when(conversations.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        scheduler.purgeExpiredConversations();
        ArgumentCaptor<LambdaQueryWrapper<Conversation>> selection = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(conversations).selectList(selection.capture());
        MybatisConfiguration configuration = new MybatisConfiguration();
        var source = new XMLLanguageDriver().createSqlSource(configuration,
                "SELECT id FROM conversation ${ew.customSqlSegment}", Map.class);
        var parameters = Map.of("ew", selection.getValue());
        var boundSql = source.getBoundSql(parameters);
        var statement = new MappedStatement.Builder(configuration, "retentionCandidates", source,
                SqlCommandType.SELECT).build();

        // 独占内存库在连接关闭时销毁；前 101 个会话长期待投递，后面 101 个满足清理条件。
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:retention_" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (var schema = connection.createStatement()) {
                schema.execute("CREATE TABLE conversation (id BIGINT PRIMARY KEY, status VARCHAR(20), "
                        + "updated_at TIMESTAMP, active_turn_task_id VARCHAR(50))");
                schema.execute("CREATE TABLE query_task (conversation_id BIGINT, cancellation_delivery_status VARCHAR(16))");
            }
            try (var insertConversation = connection.prepareStatement(
                    "INSERT INTO conversation VALUES (?, 'ARCHIVED', ?, NULL)");
                 var insertTask = connection.prepareStatement("INSERT INTO query_task VALUES (?, ?)")) {
                for (long id = 1; id <= 202; id++) {
                    insertConversation.setLong(1, id);
                    insertConversation.setObject(2, LocalDateTime.now().minusDays(400));
                    insertConversation.executeUpdate();
                    if (id <= 103) {
                        insertTask.setLong(1, id);
                        insertTask.setString(2, id <= 101 ? "PENDING" : id == 102 ? "ACCEPTED" : null);
                        insertTask.executeUpdate();
                    }
                }
            }

            // LIMIT 100 应作用于可清理候选；已接收、历史 NULL 和无任务的会话仍可进入批次。
            List<Long> candidates = new ArrayList<>();
            try (var query = connection.prepareStatement(boundSql.getSql())) {
                new DefaultParameterHandler(statement, parameters, boundSql).setParameters(query);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) candidates.add(rows.getLong(1));
                }
            }
            assertThat(candidates).containsExactlyElementsOf(LongStream.rangeClosed(102, 201).boxed().toList());
        }
    }
}
