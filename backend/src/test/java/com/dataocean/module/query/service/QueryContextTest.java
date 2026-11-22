package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.knowledge.service.impl.KnowledgeVersionUse;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * 验证多轮上下文的轮数、摘要和当前权限过滤。
 */
class QueryContextTest {

    /** 旧轮次移到有来源的结构化摘要，不传原数据表或失效口径。 */
    @Test
    void keepsFiveRecentTurnsAndSummarizesEarlierAllowedPlans() {
        var rows = mock(QueryRequestMapper.class);
        var versions = mock(KnowledgeVersionUse.class);
        var json = new JsonCodec(JsonMapper.builder().build());
        var anchor = LocalDateTime.now(ZoneOffset.UTC);
        var reasoning = json.write(new Reasoning(
                null,
                "",
                List.of(),
                List.of(),
                new Plan("READY", null, "1", "SUM", "合计", null, List.of(), List.of()),
                null));
        var current = request("now", "7", "scope", 1, anchor, reasoning);
        for (int i = 1; i <= 7; i++) {
            // 第四轮的规则已变化，不能参与近期上下文和旧轮摘要。
            var row = request(
                    Integer.toString(i),
                    i == 1 ? null : Integer.toString(i - 1),
                    i == 4 ? "obsolete" : "scope",
                    1,
                    anchor,
                    reasoning);
            when(rows.get(Integer.toString(i))).thenReturn(row);
        }
        var context = new QueryContext(rows, versions, json);
        var memory = context.capture(current, "scope");
        assertThat(memory.recent()).extracting(MemoryTurn::requestId).containsExactly("2", "3", "5", "6", "7");
        assertThat(memory.earlier()).extracting(MemoryTurn::requestId).containsExactly("1");
        assertThat(memory.earlier().get(0).question()).isNull();
        assertThat(memory.earlier().get(0).plan().metric()).isEqualTo("合计");
        when(versions.requireForQuery(1, 1)).thenThrow(BizIllegalException.denied());
        assertThatThrownBy(() -> context.verify(current, "scope", memory)).isInstanceOf(QueryFailure.class);
    }

    /** 跨源的链头即使使用相同会话标识也不会被纳入。 */
    @Test
    void rejectsHistoryOutsideTheExactSource() {
        var rows = mock(QueryRequestMapper.class);
        var versions = mock(KnowledgeVersionUse.class);
        var json = new JsonCodec(JsonMapper.builder().build());
        var anchor = LocalDateTime.now(ZoneOffset.UTC);
        var current = request("now", "older", "scope", 1, anchor, "{}");
        var other = request("older", null, "scope", 2, anchor, "{}");
        when(rows.get("older")).thenReturn(other);
        assertThat(new QueryContext(rows, versions, json)
                        .capture(current, "scope")
                        .recent())
                .isEmpty();
    }

    /**
     * 换题后仍保留这一轮的对话位置，追问看不到旧主题的已交付口径。
     */
    @Test
    void stopsReadingHistoryAtExplicitTopicChanges() {
        var rows = mock(QueryRequestMapper.class);
        var versions = mock(KnowledgeVersionUse.class);
        var json = new JsonCodec(JsonMapper.builder().build());
        var anchor = LocalDateTime.now(ZoneOffset.UTC);
        var context = new QueryContext(rows, versions, json);
        var reasoning = json.write(new Reasoning(
                null, "", List.of(), List.of(),
                new Plan("READY", null, "1", "SUM", "合计", null, List.of(), List.of()), null));
        var newTopic = request("new", "old", "scope", 1, anchor, reasoning);
        when(newTopic.question()).thenReturn("换个问题。常温饮料有多少种？");
        when(rows.get("new")).thenReturn(newTopic);
        var followup = request("followup", "new", "scope", 1, anchor, "{}");

        assertThat(context.capture(newTopic, "scope").recent()).isEmpty();
        assertThat(context.capture(followup, "scope").recent()).extracting(MemoryTurn::requestId)
                .containsExactly("new");
        assertThat(context.adjacentDay("那前一天呢？")).isEqualTo(-1);
        assertThat(context.adjacentDay("再后1天呢")).isEqualTo(1);
        assertThat(context.adjacentDay("昨天呢？")).isNull();
        assertThat(context.adjacentDay("前一天只看 2 号仓。")).isNull();
    }

    /** 构造不携带业务明细的权威历史记录模块替身。 */
    private Request request(
            String id, String previous, String signature, long source, LocalDateTime time, String reasoning) {
        var row = mock(Request.class);
        when(row.id()).thenReturn(id);
        when(row.sourceId()).thenReturn(source);
        when(row.userId()).thenReturn(7L);
        when(row.conversationId()).thenReturn("conversation");
        when(row.previousId()).thenReturn(previous);
        when(row.inheritContext()).thenReturn(true);
        when(row.state()).thenReturn("RESULT_DELIVERED");
        when(row.ruleSignature()).thenReturn(signature);
        when(row.bindingId()).thenReturn(1L);
        when(row.question()).thenReturn("求合计");
        when(row.reasoning()).thenReturn(reasoning);
        when(row.result()).thenReturn("{}");
        when(row.createdAt()).thenReturn(time);
        when(row.executedAt()).thenReturn(time);
        return row;
    }
}
