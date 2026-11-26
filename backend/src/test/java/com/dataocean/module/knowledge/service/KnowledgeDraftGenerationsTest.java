package com.dataocean.module.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Section;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Basis;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Brief;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Change;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Original;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Response;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Snapshot;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 正文输出只能指回主动依据和原本存在的章节。
 */
class KnowledgeDraftGenerationsTest {

    /**
     * 一项主动确认和一项补齐依赖，正文只有前者可用作引用根。
     */
    private final Brief brief = new Brief("库存", "核对库存", List.of(), List.of(), List.of(),
            List.of(new Basis(1, true, null), new Basis(2, false, null)));

    /**
     * 新建时至少一节、有真实主动依据，且不能覆盖原章节。
     */
    @Test
    void newDraftRequiresGroundedSections() {
        var fresh = new Snapshot(7L, brief, null);
        assertThat(KnowledgeDraftGenerations.valid(result(null, 1), fresh)).isTrue();
        assertThat(KnowledgeDraftGenerations.valid(result(null, 2), fresh)).isFalse();
        assertThat(KnowledgeDraftGenerations.valid(result(null, 9), fresh)).isFalse();
        assertThat(KnowledgeDraftGenerations.valid(result(0, 1), fresh)).isFalse();
        assertThat(KnowledgeDraftGenerations.valid(new Response(List.of()), fresh)).isFalse();
    }

    /**
     * 更新允许只提出一节差异或无更新，越界和同节重复替换均拒绝。
     */
    @Test
    void updatesUseExactOriginalSectionIndexes() {
        var old = new Original("8", "9", 2, List.of(new Section("原始说明", "人工正文")));
        var update = new Snapshot(7L, brief, old);
        var change = result(0, 1).changes().get(0);
        assertThat(KnowledgeDraftGenerations.valid(result(0, 1), update)).isTrue();
        assertThat(KnowledgeDraftGenerations.valid(result(null, 1), update)).isTrue();
        assertThat(KnowledgeDraftGenerations.valid(new Response(List.of()), update)).isTrue();
        assertThat(KnowledgeDraftGenerations.valid(result(1, 1), update)).isFalse();
        assertThat(KnowledgeDraftGenerations.valid(new Response(List.of(change, change)), update)).isFalse();
    }

    /**
     * 用固定正文单独验证章节和依据身份。
     */
    private Response result(Integer sectionIndex, int basis) {
        return new Response(List.of(new Change(sectionIndex, "当前库存说明", "一行表示当前库存", List.of(basis))));
    }
}
