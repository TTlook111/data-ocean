package com.dataocean.common.pagination;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageRequestTest {

    @Test
    void pageNormalizesInvalidValues() {
        assertThat(PageRequest.page(null)).isEqualTo(1L);
        assertThat(PageRequest.page(0)).isEqualTo(1L);
        assertThat(PageRequest.page(3)).isEqualTo(3L);
    }

    @Test
    void sizeDefaultsAndCapsLargeValues() {
        assertThat(PageRequest.size(null)).isEqualTo(20L);
        assertThat(PageRequest.size(0)).isEqualTo(20L);
        assertThat(PageRequest.size(500)).isEqualTo(100L);
        assertThat(PageRequest.size(50)).isEqualTo(50L);
    }
}
