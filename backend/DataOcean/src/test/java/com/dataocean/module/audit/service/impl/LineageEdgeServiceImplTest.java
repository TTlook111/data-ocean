package com.dataocean.module.audit.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.audit.entity.dto.ColumnMappingItem;
import com.dataocean.module.audit.entity.dto.LineageCreateRequest;
import com.dataocean.module.metadata.entity.MetadataRelationship;
import com.dataocean.module.metadata.mapper.MetadataRelationshipMapper;
import com.dataocean.module.metadata.service.MetadataEntityService;
import com.dataocean.module.metadata.service.MetadataRelationshipService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次 6：血缘写入必须逐项校验源/目标实体负责范围，任一失败整批零写入。
 */
@ExtendWith(MockitoExtension.class)
class LineageEdgeServiceImplTest {

    @Mock
    private MetadataEntityService entityService;
    @Mock
    private MetadataRelationshipService relationshipService;
    @Mock
    private MetadataRelationshipMapper relationshipMapper;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private IamS1AdminGuard adminGuard;
    @InjectMocks
    private LineageEdgeServiceImpl service;

    @BeforeEach
    void login() {
        LoginUser loginUser = new LoginUser(
                7L, "alice", "x", "Alice",
                List.of("ROLE"), List.of("lineage:manage"),
                List.of(new SimpleGrantedAuthority("lineage:manage")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void batchCreateRejectsCrossSourceAndWritesNothing() {
        LineageCreateRequest owned = request(10L, 11L);
        LineageCreateRequest foreign = request(20L, 21L);
        when(entityService.getDatasourceIdByEntityId(10L)).thenReturn(5L);
        when(entityService.getDatasourceIdByEntityId(11L)).thenReturn(5L);
        when(entityService.getDatasourceIdByEntityId(20L)).thenReturn(6L);
        when(entityService.getDatasourceIdByEntityId(21L)).thenReturn(6L);
        denyDatasource(6L);

        assertThatThrownBy(() -> service.batchCreateLineage(List.of(owned, foreign)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");

        verify(relationshipService, never()).upsert(any());
    }

    @Test
    void batchCreateRejectsColumnMappingOnUnmanagedSource() {
        LineageCreateRequest request = request(10L, 11L);
        ColumnMappingItem mapping = new ColumnMappingItem();
        mapping.setFromColumns(List.of(301L));
        mapping.setToColumn(401L);
        request.setColumnMappings(List.of(mapping));
        when(entityService.getDatasourceIdByEntityId(10L)).thenReturn(5L);
        when(entityService.getDatasourceIdByEntityId(11L)).thenReturn(5L);
        when(entityService.getDatasourceIdByEntityId(301L)).thenReturn(5L);
        when(entityService.getDatasourceIdByEntityId(401L)).thenReturn(6L);
        denyDatasource(6L);

        assertThatThrownBy(() -> service.batchCreateLineage(List.of(request)))
                .isInstanceOf(BusinessException.class);

        verify(relationshipService, never()).upsert(any());
    }

    @Test
    void deleteLineageRejectsWhenTargetEntityIsOutsideResponsibleScope() {
        MetadataRelationship rel = new MetadataRelationship();
        rel.setId(99L);
        rel.setRelationType(MetadataRelationship.TYPE_LINEAGE);
        rel.setSourceId(10L);
        rel.setTargetId(20L);
        when(relationshipService.getById(99L)).thenReturn(rel);
        when(entityService.getDatasourceIdByEntityId(10L)).thenReturn(5L);
        when(entityService.getDatasourceIdByEntityId(20L)).thenReturn(6L);
        denyDatasource(6L);

        assertThatThrownBy(() -> service.deleteLineage(99L, false))
                .isInstanceOf(BusinessException.class);

        verify(relationshipService, never()).removeById(anyLong());
        verify(relationshipService, never()).removeById(eq(99L));
    }

    private void denyDatasource(Long forbiddenDatasourceId) {
        doAnswer(invocation -> {
            Long datasourceId = invocation.getArgument(2);
            if (forbiddenDatasourceId.equals(datasourceId)) {
                throw new BusinessException(403, "无权维护该数据源的血缘");
            }
            return null;
        }).when(adminGuard).requireDatasourceFunction(eq(7L), eq("lineage:manage"), anyLong());
    }

    private static LineageCreateRequest request(Long sourceId, Long targetId) {
        LineageCreateRequest request = new LineageCreateRequest();
        request.setSourceEntityId(sourceId);
        request.setTargetEntityId(targetId);
        request.setLineageType("ETL");
        return request;
    }
}
