package com.dataocean.module.metadata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.TableRelation;
import com.dataocean.module.metadata.entity.dto.ManualJoinPathRequest;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.TableRelationMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualJoinPathControllerTest {
    @AfterEach
    void clear() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualJoinIsSavedAsAnExplicitConfirmedFactOnTheSelectedSnapshot() {
        Fixture fixture = new Fixture();
        fixture.login();
        fixture.publishedSnapshot();
        fixture.tablesAndColumns();

        TableRelation relation = fixture.controller.create(5L, fixture.request()).getData();

        assertThat(relation.getRelationType()).isEqualTo(TableRelation.TYPE_MANUAL);
        assertThat(relation.getReviewStatus()).isEqualTo("CONFIRMED");
        assertThat(relation.getReviewedBy()).isEqualTo(7L);
        assertThat(relation.getSnapshotId()).isEqualTo(88L);
        verify(fixture.relationMapper).insert(any(TableRelation.class));
    }

    @Test
    void manualJoinRejectsColumnsNotInTheSelectedSnapshot() {
        Fixture fixture = new Fixture();
        fixture.login();
        fixture.publishedSnapshot();
        fixture.tablesAndColumns();
        when(fixture.columnMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> fixture.controller.create(5L, fixture.request()))
                .hasMessageContaining("不存在");
        verify(fixture.relationMapper, never()).insert(any(TableRelation.class));
    }

    private static final class Fixture {
        private final TableRelationMapper relationMapper = mock(TableRelationMapper.class);
        private final MetadataSnapshotMapper snapshotMapper = mock(MetadataSnapshotMapper.class);
        private final DbTableMetaMapper tableMapper = mock(DbTableMetaMapper.class);
        private final DbColumnMetaMapper columnMapper = mock(DbColumnMetaMapper.class);
        private final ManualJoinPathController controller =
                new ManualJoinPathController(relationMapper, snapshotMapper, tableMapper, columnMapper);

        ManualJoinPathRequest request() {
            ManualJoinPathRequest request = new ManualJoinPathRequest();
            request.setSnapshotId(88L);
            request.setSourceTable("sales_orders");
            request.setSourceColumn("product_id");
            request.setTargetTable("products");
            request.setTargetColumn("product_id");
            request.setConfirmed(true);
            return request;
        }

        void login() {
            LoginUser user = new LoginUser(7L, "tester", "password", "测试员", List.of());
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        }

        void publishedSnapshot() {
            MetadataSnapshot snapshot = new MetadataSnapshot();
            snapshot.setId(88L);
            snapshot.setDatasourceId(5L);
            snapshot.setStatus(MetadataSnapshot.STATUS_PUBLISHED);
            when(snapshotMapper.selectById(88L)).thenReturn(snapshot);
        }

        void tablesAndColumns() {
            DbTableMeta source = new DbTableMeta();
            source.setId(10L);
            source.setTableName("sales_orders");
            DbTableMeta target = new DbTableMeta();
            target.setId(20L);
            target.setTableName("products");
            when(tableMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(source, target);
            when(columnMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
            when(relationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        }
    }
}
