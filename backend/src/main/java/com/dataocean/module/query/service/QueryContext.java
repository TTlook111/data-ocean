package com.dataocean.module.query.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.MemoryTurn;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 只从本人同源会话中读取仍获准且可追溯的历史口径。
 */
@Service
@RequiredArgsConstructor
class QueryContext {

    /**
     * 明确换题的起始语用于截断此前主题，包含这轮之后的回看。
     */
    private static final Pattern NEW_TOPIC =
            Pattern.compile("^\\s*(?:换(?:个|一个|一道)问题|(?:另|再)问(?:个|一个)问题|新问题|换(?:个|一个)话题)[，,。.!！：:\\s]");

    /**
     * 仅识别只修改前后一天的短追问，保留带有其他条件的自然语言处理。
     */
    private static final Pattern ADJACENT_DAY =
            Pattern.compile("^\\s*(?:那(?:么)?|再)?\\s*([前后上下])(?:一|1)天\\s*(?:呢|的呢)?[？?。.!！\\s]*$");

    /** 原始轮次只从权威库回读。 */
    private final QueryRequestMapper requests;
    /** 历史绑定仍须满足当前使用资格。 */
    private final KnowledgeVersionService versions;
    /** 固化的实际执行计划用于还原条件。 */
    private final JsonCodec json;

    /** 请求锚点使用真实接受时间，重启和补充不重新计算日期。 */
    Memory capture(Request row, String ruleSignature) {
        var recent = new ArrayList<MemoryTurn>();
        var earlier = new ArrayList<MemoryTurn>();
        String previous = inherits(row) ? row.previousId() : null;
        for (int i = 0; previous != null && i < 24; i++) {
            var turn = requests.get(previous);
            if (!belongs(row, turn)) {
                break;
            }
            previous = inherits(turn) ? turn.previousId() : null;
            if (!usable(turn, ruleSignature)) {
                continue;
            }
            var actual = json.read(turn.reasoning(), Reasoning.class);
            var plan = actual.plan();
            if (recent.size() < 5) {
                // 原话与补充只来自仍可用的已交付轮次，业务结果本体不进入下一次外发。
                recent.add(new MemoryTurn(turn.id(), turn.question(), turn.supplement(), plan));
            } else if (plan != null && earlier.size() < 10) {
                // 更早只保存经核验的指标与条件摘要，保留原请求来源。
                earlier.add(new MemoryTurn(turn.id(), null, null, plan));
            }
        }
        Collections.reverse(recent);
        Collections.reverse(earlier);
        return new Memory(
                row.createdAt().toInstant(ZoneOffset.UTC).toString(), List.copyOf(recent), List.copyOf(earlier));
    }

    /**
     * 只对当前已确认的主题沿用上下文，保留同一对话的历史导航。
     */
    private boolean inherits(Request row) {
        return row.inheritContext() && !NEW_TOPIC.matcher(row.question()).find();
    }

    /**
     * 判定纯相邻日追问的偏移方向，前天和昨天等绝对相对日继续用提问时钟。
     */
    Integer adjacentDay(String question) {
        var match = ADJACENT_DAY.matcher(question);
        if (!match.matches()) {
            // 更具体的问句仍交给普通计划节点处理。
            return null;
        }
        return "前".equals(match.group(1)) || "上".equals(match.group(1)) ? -1 : 1;
    }

    /** 已冻结历史在后续调用前重新核对，失效时宁可停止也不继续外发。 */
    void verify(Request row, String rules, Memory memory) {
        for (var item : java.util.stream.Stream.concat(memory.recent().stream(), memory.earlier().stream())
                .toList()) {
            var turn = requests.get(item.requestId());
            if (!belongs(row, turn) || !usable(turn, rules)) {
                throw QueryFailure.knowledge();
            }
        }
    }

    /**
     * 从仍获准的原轮次读取实际执行血缘，仅供服务端核验历史追问。
     */
    QueryUsage executionUsage(Request row, String rules, MemoryTurn previous) {
        var turn = requests.get(previous.requestId());
        if (!belongs(row, turn)
                || !usable(turn, rules)
                || !Objects.equals(
                        previous.plan(),
                        json.read(turn.reasoning(), Reasoning.class).plan())) {
            // 权威记录与冻结计划不一致时，不能继续沿用。
            throw QueryFailure.knowledge();
        }
        if (turn.usageRefs() == null) {
            // 旧轮次缺少执行血缘时，由工作者结算知识不足。
            return null;
        }
        var usage = json.read(turn.usageRefs(), QueryUsage.class);
        return usage == null || usage.objects() == null || usage.objects().isEmpty() ? null : usage;
    }

    /** 用户、来源和对话三个边界必须同时满足。 */
    private boolean belongs(Request current, Request older) {
        return older != null
                && current.userId() == older.userId()
                && current.sourceId() == older.sourceId()
                && Objects.equals(current.conversationId(), older.conversationId());
    }

    /** 历史权限、输出限制或正式知识变化会使对应上下文不可用。 */
    private boolean usable(Request turn, String rules) {
        if (!"RESULT_DELIVERED".equals(turn.state())
                || turn.result() == null
                || turn.bindingId() == null
                || turn.reasoning() == null
                || !rules.equals(turn.ruleSignature())
                || turn.executedAt() == null
                || !turn.executedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))) {
            return false;
        }
        try {
            versions.requireForQuery(turn.sourceId(), turn.bindingId());
            return true;
        } catch (BizIllegalException changed) {
            // 历史旧绑定或禁止范围不向后继请求传递。
            return false;
        }
    }
}
