package com.dataocean.module.knowledge.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeDocumentPartitionerTest {
    @Test void splitsTopicsWithoutDroppingSourceMarkersOrProse() {
        String content = "# Source\n\n## 1. 来源\n范围说明\n\n## 2. 字段目录\n### orders\n<!-- dataocean-fact: {\"factType\":\"TABLE_STRUCTURE\"} -->\n正文\n\n## 3. 表关系\n### orders-products\n<!-- dataocean-fact: {\"factType\":\"JOIN_PATH\"} -->\n关联条件\n";
        var parts = KnowledgeDocumentPartitioner.split(content);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).content()).contains("范围说明", "TABLE_STRUCTURE");
        assertThat(parts.get(1).content()).contains("JOIN_PATH", "关联条件");
        assertThat(parts.get(1).title()).contains("表关系");
    }
}
