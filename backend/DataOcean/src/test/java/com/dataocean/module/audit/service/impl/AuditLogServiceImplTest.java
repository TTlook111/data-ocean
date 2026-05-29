package com.dataocean.module.audit.service.impl;

import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceImplTest {

    @Test
    void recordAuditCopiesPromptVersionsFromQueryTask() {
        QueryAuditLogMapper auditLogMapper = mock(QueryAuditLogMapper.class);
        QueryTaskMapper queryTaskMapper = mock(QueryTaskMapper.class);
        AuditLogServiceImpl service = new AuditLogServiceImpl(auditLogMapper, queryTaskMapper);

        QueryTask task = new QueryTask();
        task.setId(10L);
        task.setUserId(7L);
        task.setDatasourceId(3L);
        task.setQuestion("last month revenue");
        task.setStatus("COMPLETED");
        task.setPromptVersions("[{\"templateCode\":\"sql_generation\",\"versionNo\":2}]");
        when(queryTaskMapper.selectById(10L)).thenReturn(task);

        service.recordAudit(10L);

        ArgumentCaptor<QueryAuditLog> captor = ArgumentCaptor.forClass(QueryAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getPromptVersions())
                .isEqualTo("[{\"templateCode\":\"sql_generation\",\"versionNo\":2}]");
    }
}
