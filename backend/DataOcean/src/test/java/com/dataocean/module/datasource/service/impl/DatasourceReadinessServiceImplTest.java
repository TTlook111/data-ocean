package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourceReadinessServiceImplTest {

    @Mock private DatasourceMapper datasourceMapper;
    @Mock private MetadataSnapshotMapper snapshotMapper;
    @Mock private KnowledgeDocMapper knowledgeDocMapper;
    @Mock private IamS1DataGrantMapper iamS1DataGrantMapper;
    @Mock private DbTableMetaMapper tableMetaMapper;
    @Mock private DbColumnMetaMapper columnMetaMapper;
    @Mock private IamS1DataAuthorizationResolver iamS1DataAuthorizationResolver;
    @InjectMocks private DatasourceReadinessServiceImpl service;

    @Test
    void adminReadinessUsesActiveS1AllowGrantForPermissionReadiness() {
        Datasource datasource = new Datasource();
        datasource.setId(7L);
        datasource.setStatus(Datasource.STATUS_ENABLED);
        datasource.setHealthStatus(Datasource.HEALTH_HEALTHY);
        when(datasourceMapper.selectById(7L)).thenReturn(datasource);
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(knowledgeDocMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(iamS1DataGrantMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        var readiness = service.getAdminReadiness(7L);

        assertThat(readiness.isPermissionReady()).isTrue();
        verify(iamS1DataGrantMapper).selectCount(any(LambdaQueryWrapper.class));
    }
}
