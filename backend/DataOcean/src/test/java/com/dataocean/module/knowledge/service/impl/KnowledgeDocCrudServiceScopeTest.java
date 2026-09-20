package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 5 聚焦测试：知识文档列表的负责源范围。
 *
 * <p>范围必须**下推到 SQL**：先分页再在内存过滤会让总数与分页边界出错。
 * 调用方显式筛选一个自己无权的数据源时直接 403，而不是返回空页——空页会把
 * 「无权」伪装成「该数据源没有文档」（与批次 4 对指定无权快照的处理一致）。</p>
 */
class KnowledgeDocCrudServiceScopeTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeDoc.class
        );
    }

    @Test
    void emptyResponsibleScopeReturnsAnEmptyPageWithoutQuerying() {
        Fixture fixture = new Fixture();

        Page<KnowledgeDoc> page = fixture.service.listDocsInDatasources(List.of(), null, null, 1, 10);

        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        verify(fixture.docMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void responsibleScopeIsPushedIntoTheSql() {
        Fixture fixture = new Fixture();
        fixture.emptyPage();

        fixture.service.listDocsInDatasources(List.of(5L, 6L), null, null, 1, 10);

        assertThat(fixture.capturedSegment()).contains("datasource_id");
    }

    @Test
    void requestedDatasourceOutsideTheScopeIsRejectedInsteadOfReturningAnEmptyPage() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.listDocsInDatasources(List.of(5L), 6L, null, 1, 10))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(403));

        verify(fixture.docMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void requestedDatasourceInsideTheScopeNarrowsTheQuery() {
        Fixture fixture = new Fixture();
        fixture.emptyPage();

        fixture.service.listDocsInDatasources(List.of(5L, 6L), 6L, null, 1, 10);

        String segment = fixture.capturedSegment();
        assertThat(segment).contains("datasource_id");
        // 范围 + 显式筛选两个条件都在：范围收窄后仍按请求的数据源过滤
        assertThat(segment.split("datasource_id", -1).length - 1).isGreaterThanOrEqualTo(1);
    }

    private static final class Fixture {
        private final KnowledgeDocMapper docMapper = mock(KnowledgeDocMapper.class);
        private final KnowledgeDocCrudService service = new KnowledgeDocCrudService(
                docMapper,
                mock(KnowledgeDocVersionMapper.class),
                mock(KnowledgeDependencySnapshotBuilder.class),
                mock(KnowledgeDocHelper.class));

        void emptyPage() {
            when(docMapper.selectPage(any(Page.class), any(Wrapper.class)))
                    .thenReturn(new Page<KnowledgeDoc>().setRecords(List.of()));
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        String capturedSegment() {
            ArgumentCaptor<Wrapper<KnowledgeDoc>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(docMapper).selectPage(any(Page.class), captor.capture());
            return captor.getValue().getCustomSqlSegment();
        }
    }
}
