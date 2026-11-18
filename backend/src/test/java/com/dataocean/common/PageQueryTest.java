package com.dataocean.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证先计数再分页的列表保留原有页码契约。
 */
class PageQueryTest {

    /**
     * 越界时定位最后有效页，并沿用已统计的总数。
     */
    @Test
    void clampsToLastPageWithoutCountingAgain() {
        var query = new PageQuery().setPageNum(9L).setPageSize(2L);
        var page = query.toLastAvailableMpPage(5L);
        assertThat(page.getCurrent()).isEqualTo(3L);
        assertThat(page.offset()).isEqualTo(4L);
        assertThat(page.getTotal()).isEqualTo(5L);
        assertThat(page.searchCount()).isFalse();
    }

    /**
     * 空结果固定在第一页，合法页码和页大小保持原值。
     */
    @Test
    void keepsFirstPageForEmptyResultsAndRespectsValidPage() {
        var query = new PageQuery().setPageNum(2L).setPageSize(3L);
        assertThat(query.toLastAvailableMpPage(0).getCurrent()).isEqualTo(1L);
        assertThat(query.toLastAvailableMpPage(5).getCurrent()).isEqualTo(2L);
        assertThat(query.toLastAvailableMpPage(5).getSize()).isEqualTo(3L);
    }
}
