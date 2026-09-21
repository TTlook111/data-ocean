package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.MetadataReviewRecord;
import com.dataocean.module.governance.mapper.MetadataReviewRecordMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
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
 * 批次 4 聚焦测试：表/列治理状态必须落在被授权的那个快照上。
 *
 * <p>`@IamS1Resource(SNAPSHOT)` 只校验调用者负责该快照的数据源；
 * “字段/表是否真的属于这个快照”必须由 Service 再判一次，否则可以用
 * 一个有权快照的 URL 去改另一个快照下的字段——归属校验与资源校验分属两层，缺一不可。</p>
 */
class GovernanceStatusServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DbColumnMeta.class
        );
    }

    @Test
    void updateColumnStatusRejectsAColumnFromAnotherSnapshot() {
        Fixture fixture = new Fixture();
        DbColumnMeta column = new DbColumnMeta();
        column.setId(11L);
        column.setSnapshotId(7L);
        column.setTableName("orders");
        column.setColumnName("amount");
        when(fixture.columnMetaMapper.selectById(11L)).thenReturn(column);

        // 请求走快照 8 的 URL，但要改的是快照 7 下的字段
        assertThatThrownBy(() -> fixture.service.updateColumnStatus(
                8L, 11L, "BLOCKED", 99L, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(404));

        verify(fixture.columnMetaMapper, never()).updateById(any(DbColumnMeta.class));
        verify(fixture.reviewRecordMapper, never()).insert(any(MetadataReviewRecord.class));
    }

    @Test
    void updateColumnStatusAllowsAColumnInsideTheSnapshot() {
        Fixture fixture = new Fixture();
        DbColumnMeta column = new DbColumnMeta();
        column.setId(11L);
        column.setSnapshotId(8L);
        column.setTableName("orders");
        column.setColumnName("amount");
        when(fixture.columnMetaMapper.selectById(11L)).thenReturn(column);

        fixture.service.updateColumnStatus(8L, 11L, "BLOCKED", 99L, null);

        assertThat(column.getGovernanceStatus()).isEqualTo("BLOCKED");
        verify(fixture.columnMetaMapper).updateById(column);
    }

    @Test
    void batchUpdateColumnStatusScopesBySnapshotAndTable() {
        Fixture fixture = new Fixture();
        when(fixture.columnMetaMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        fixture.service.batchUpdateColumnStatus(8L, "orders", "NORMAL", 99L, null, null);

        // 批量范围必须同时按 snapshot 与 table 下推 SQL：
        // 只用其中一个会让批量操作跨快照或跨表改到未被授权的字段
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Wrapper<DbColumnMeta>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(fixture.columnMetaMapper).selectList(captor.capture());
        assertThat(captor.getValue().getCustomSqlSegment())
                .contains("snapshot_id")
                .contains("table_name");
    }

    private static final class Fixture {
        private final DbTableMetaMapper tableMetaMapper = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columnMetaMapper = mock(DbColumnMetaMapper.class);
        private final MetadataReviewRecordMapper reviewRecordMapper = mock(MetadataReviewRecordMapper.class);
        private final GovernanceStatusServiceImpl service = new GovernanceStatusServiceImpl(
                tableMetaMapper, columnMetaMapper, reviewRecordMapper);
    }
}
