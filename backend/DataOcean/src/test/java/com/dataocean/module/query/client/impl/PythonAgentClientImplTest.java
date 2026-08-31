package com.dataocean.module.query.client.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PythonAgentClientImplTest {

    @Test
    void fallbackCacheKeyIsStableForSameQuestionAndSeparatedForDifferentQuestions() {
        String first = PythonAgentClientImpl.buildFallbackChunksCacheKey(10L, 5L, "查询订单");
        String same = PythonAgentClientImpl.buildFallbackChunksCacheKey(10L, 5L, " 查询订单 ");
        String different = PythonAgentClientImpl.buildFallbackChunksCacheKey(10L, 5L, "查询客户");

        assertThat(first).isEqualTo(same);
        assertThat(first).isNotEqualTo(different);
        assertThat(first).startsWith("fallback:chunks:10:5:");
    }
}
