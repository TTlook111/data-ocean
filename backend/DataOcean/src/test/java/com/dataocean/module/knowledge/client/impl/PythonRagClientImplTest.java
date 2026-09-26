package com.dataocean.module.knowledge.client.impl;

import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PythonRagClientImplTest {

    @Test
    void buildVectorPayloadCarriesTheExactSourceSnapshotId() {
        PythonRagClientImpl client = new PythonRagClientImpl(null);
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .id(71L)
                .metadataSnapshotId(8801L)
                .chunkType("FIELD_NOTE")
                .chunkText("snapshot-scoped fact")
                .resourceDependencies("[\"column:orders.order_id\"]")
                .factSourceIds("[\"column:orders.order_id\"]")
                .factType("COLUMN_STRUCTURE")
                .factReviewStatus("APPROVED")
                .reviewStatus("APPROVED")
                .governanceStatus("NORMAL")
                .build();

        assertThat(client.toChunkPayload(chunk))
                .containsEntry("sourceSnapshotId", 8801L)
                .containsEntry("factReviewStatus", "APPROVED")
                .containsEntry("resourceDependencies", "[\"column:orders.order_id\"]")
                .containsEntry("factSourceIds", "[\"column:orders.order_id\"]");
    }
}
