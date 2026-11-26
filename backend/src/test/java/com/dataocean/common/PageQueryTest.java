package com.dataocean.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.metadata.OrderItem;

import org.junit.jupiter.api.Test;

/**
 * 验证服务端固定排序列表的分页参数交由插件处理。
 */
class PageQueryTest {

    /**
     * 带默认排序的查询也将极大页码交给分页插件统一校正。
     */
    @Test
    void leavesOffsetCalculationToPluginWithDefaultSort() {
        var query = new PageQuery().setPageNum(Long.MAX_VALUE).setPageSize(2L);
        var page = query.toMpPage("updated_at", false);
        assertThat(page.getCurrent()).isEqualTo(Long.MAX_VALUE);
        assertThat(page.searchCount()).isTrue();
        assertThat(page.orders()).extracting(OrderItem::getColumn).containsExactly("updated_at");
    }

    /**
     * 极大页码在取数前由计数校正，服务端固定排序覆盖外部排序。
     */
    @Test
    void leavesPageClampingToThePaginationInterceptor() {
        var query = new PageQuery().setPageNum(Long.MAX_VALUE).setPageSize(2L).setSortBy("username");
        var page = query.toFixedOrderMpPage(OrderItem.desc("created_at"), OrderItem.desc("id"));
        assertThat(page.getCurrent()).isEqualTo(Long.MAX_VALUE);
        assertThat(page.getSize()).isEqualTo(2L);
        assertThat(page.searchCount()).isTrue();
        assertThat(page.orders()).extracting(OrderItem::getColumn).containsExactly("created_at", "id");
    }
}
