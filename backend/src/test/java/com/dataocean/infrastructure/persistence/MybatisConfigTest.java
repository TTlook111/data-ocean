package com.dataocean.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 验证分页拦截器在计数结果确定后调整页码。
 */
class MybatisConfigTest {

    /**
     * 覆盖末页、空结果和合法页码，确保取数使用校正后的偏移量。
     */
    @ParameterizedTest
    @CsvSource({"9, 5, 3, true", "9, 0, 1, false", "2, 5, 2, true"})
    void usesLastAvailablePage(long requested, long total, long expected, boolean fetchRecords) throws SQLException {
        var pagination =
                new MybatisConfig().mybatisPlusInterceptor().getInterceptors().get(0);

        // 使用真正的插件计数路径，仅模拟数据库返回的总数。
        var configuration = new Configuration();
        var source = new StaticSqlSource(configuration, "SELECT id FROM iam_role");
        var statement =
                new MappedStatement.Builder(configuration, "sample.list", source, SqlCommandType.SELECT).build();
        var page = new Page<>(requested, 2);
        page.setOptimizeCountSql(false);
        var executor = mock(Executor.class);
        when(executor.query(any(), eq(page), eq(RowBounds.DEFAULT), isNull(), any(), any()))
                .thenReturn(List.of(total));
        when(executor.createCacheKey(any(), eq(page), eq(RowBounds.DEFAULT), any()))
                .thenReturn(new CacheKey());

        assertThat(pagination.willDoQuery(
                        executor, statement, page, RowBounds.DEFAULT, null, statement.getBoundSql(page)))
                .isEqualTo(fetchRecords);
        assertThat(page.getCurrent()).isEqualTo(expected);
        assertThat(page.getTotal()).isEqualTo(total);
        assertThat(page.offset()).isEqualTo((expected - 1) * 2);
    }
}
