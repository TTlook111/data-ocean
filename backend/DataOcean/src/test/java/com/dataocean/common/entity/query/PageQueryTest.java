package com.dataocean.common.entity.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.audit.entity.query.AuditLogQuery;
import com.dataocean.module.datasource.entity.query.DatasourceQuery;
import com.dataocean.module.query.entity.query.QueryHistoryQuery;
import com.dataocean.module.system.entity.query.OperationLogQuery;
import com.dataocean.module.user.entity.query.UserQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.web.bind.WebDataBinder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1, 0})
    void invalidValuesUseExistingDefaults(Long value) {
        PageQuery query = new PageQuery();
        query.setPageNo(value);
        query.setPageSize(value);

        assertThat(query.toMpPage().getCurrent()).isEqualTo(1);
        assertThat(query.toMpPage().getSize()).isEqualTo(20);
        assertThat(query.from()).isZero();
    }

    @Test
    void capsSizeAndComputesOffsetWithoutIntegerOverflow() {
        PageQuery query = new PageQuery();
        query.setPageNo(30_000_001L);
        query.setPageSize(500L);

        assertThat(query.toMpPage().getSize()).isEqualTo(100);
        assertThat(query.from()).isEqualTo(3_000_000_000L);
    }

    @Test
    void rejectsOffsetOverflowInsteadOfReturningFirstPage() {
        PageQuery query = new PageQuery();
        query.setPageNo(Long.MAX_VALUE);

        assertThatThrownBy(query::toMpPage).isInstanceOf(BusinessException.class)
                .hasMessageContaining("分页偏移量");
    }

    @Test
    void bindsLegacyPageNamesAndUsesDeterministicPrecedence() {
        for (PageQuery query : businessQueries()) {
            WebDataBinder binder = new WebDataBinder(query);
            binder.bind(new MutablePropertyValues(Map.of("pageNum", "4", "pageSize", "50")));
            assertThat(binder.getBindingResult().hasErrors()).isFalse();
            assertThat(query.toMpPage().getCurrent()).isEqualTo(4);
            assertThat(query.toMpPage().getSize()).isEqualTo(50);

            binder.bind(new MutablePropertyValues(Map.of("page", "3", "pageNo", "2")));
            assertThat(query.toMpPage().getCurrent()).isEqualTo(2);
            query.setPageNo(null);
            assertThat(query.toMpPage().getCurrent()).isEqualTo(3);
        }
    }

    @Test
    void defaultsToActualCreatedAtColumnWithoutMutatingQuery() {
        for (PageQuery query : businessQueries()) {
            var page = query.toMpPageDefaultSortByCreatedAtDesc();
            assertThat(page.orders()).singleElement().satisfies(order -> {
                assertThat(order.getColumn()).isEqualTo("created_at");
                assertThat(order.isAsc()).isFalse();
            });
            assertThat(query.getSortBy()).isNull();
            assertThat(query.getIsAsc()).isTrue();
        }
    }

    @Test
    void requestedSortOverridesDefaultThroughWhitelist() {
        UserQuery query = new UserQuery();
        WebDataBinder binder = new WebDataBinder(query);
        binder.bind(new MutablePropertyValues(Map.of("sortBy", "realName", "isAsc", "false")));

        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(query.toMpPageDefaultSortByCreatedAtDesc().orders()).singleElement().satisfies(order -> {
            assertThat(order.getColumn()).isEqualTo("real_name");
            assertThat(order.isAsc()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"password_hash", "unknownColumn", "created_at desc; drop table sys_user", "(select 1)"})
    void businessQueriesRejectColumnsOutsideWhitelist(String sortBy) {
        for (PageQuery query : businessQueries()) {
            query.setSortBy(sortBy);
            assertThatThrownBy(query::toMpPageDefaultSortByCreatedAtDesc)
                    .isInstanceOf(BusinessException.class).hasMessage("不支持的排序字段");
        }
    }

    @Test
    void explicitBackendOrdersTakePrecedence() {
        UserQuery query = new UserQuery();
        query.setSortBy("username");

        assertThat(query.toMpPage(OrderItem.desc("created_at"), OrderItem.asc("id")).orders())
                .extracting(OrderItem::getColumn).containsExactly("created_at", "id");
    }

    @Test
    void absentOrNullSortArgumentsProduceUnsortedPage() {
        PageQuery query = new PageQuery();
        query.setSortBy(" ");

        assertThat(query.toMpPage().orders()).isEmpty();
        assertThat(query.toMpPage((OrderItem[]) null).orders()).isEmpty();
    }

    private List<PageQuery> businessQueries() {
        return List.of(new UserQuery(), new DatasourceQuery(), new QueryHistoryQuery(),
                new AuditLogQuery(), new OperationLogQuery());
    }
}
