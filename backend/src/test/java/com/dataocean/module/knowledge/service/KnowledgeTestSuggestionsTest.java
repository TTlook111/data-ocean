package com.dataocean.module.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Response;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Suggestion;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 模型候选只能引用确切确认中的章节与已有题单类型。
 */
class KnowledgeTestSuggestionsTest {

    /**
     * 可以返回空建议，实际题目则必须保留可追溯依据。
     */
    @Test
    void requiresExistingSectionsForEachCandidate() {
        assertThat(KnowledgeTestSuggestions.valid(new Response(List.of()), 1)).isTrue();
        assertThat(KnowledgeTestSuggestions.valid(candidate("BOUNDARY", "EXPLANATION", List.of(0)), 1)).isTrue();
        assertThat(KnowledgeTestSuggestions.valid(candidate("BOUNDARY", "EXPLANATION", List.of(1)), 1)).isFalse();
        assertThat(KnowledgeTestSuggestions.valid(candidate("BOUNDARY", "EXPLANATION", List.of()), 1)).isFalse();
    }

    /**
     * 不允许模型增加业务题型或改变审核覆盖定义。
     */
    @Test
    void rejectsUnsupportedCoverageAndMode() {
        assertThat(KnowledgeTestSuggestions.valid(candidate("CAPABILITY", "NUMERIC", List.of(0)), 1)).isTrue();
        assertThat(KnowledgeTestSuggestions.valid(candidate("NEW", "NUMERIC", List.of(0)), 1)).isFalse();
        assertThat(KnowledgeTestSuggestions.valid(candidate("CAPABILITY", "SQL", List.of(0)), 1)).isFalse();
    }

    /**
     * 用稳定问法单独验证回包类型和章节边界。
     */
    private Response candidate(String coverage, String mode, List<Integer> sections) {
        return new Response(List.of(new Suggestion("库存快照能跨日累加吗？", coverage, mode,
                "核对已确认跨日使用限制", sections)));
    }
}
