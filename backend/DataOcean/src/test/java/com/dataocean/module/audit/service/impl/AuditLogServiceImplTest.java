package com.dataocean.module.audit.service.impl;

import com.dataocean.module.audit.entity.po.QueryAuditLog;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.query.entity.po.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogServiceImplTest {

    @Test
    void committedAuditCopiesPromptVersionsFromQueryTask() {
        QueryAuditLogMapper auditLogMapper = mock(QueryAuditLogMapper.class);
        QueryTaskMapper queryTaskMapper = mock(QueryTaskMapper.class);
        AuditLogServiceImpl service = new AuditLogServiceImpl(auditLogMapper, queryTaskMapper, new com.fasterxml.jackson.databind.ObjectMapper());

        QueryTask task = new QueryTask();
        task.setId(10L);
        task.setUserId(7L);
        task.setDatasourceId(3L);
        task.setQuestion("last month revenue");
        task.setStatus("COMPLETED");
        task.setResultData("[{\"id\":1}]");
        task.setPromptVersions("[{\"templateCode\":\"sql_generation\",\"versionNo\":2}]");

        service.recordCommittedAudit(task);

        ArgumentCaptor<QueryAuditLog> captor = ArgumentCaptor.forClass(QueryAuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getPromptVersions())
                .isEqualTo("[{\"templateCode\":\"sql_generation\",\"versionNo\":2}]");
        assertThat(captor.getValue().getRowCount()).isEqualTo(1);
    }

    @Test void emptyProtectedArrayRecordsZeroRatherThanMissingEvidence(){
        var audits=mock(QueryAuditLogMapper.class);
        var service=new AuditLogServiceImpl(audits,mock(QueryTaskMapper.class),new com.fasterxml.jackson.databind.ObjectMapper());
        var task=QueryTask.builder().id(11L).status("COMPLETED").resultData("[]").build();
        service.recordCommittedAudit(task);
        var saved=ArgumentCaptor.forClass(QueryAuditLog.class);verify(audits).insert(saved.capture());
        assertThat(saved.getValue().getRowCount()).isZero();
    }

    @Test void missingProtectedDataCannotBeReinterpretedAsAnEmptySuccess(){
        var audits=mock(QueryAuditLogMapper.class);
        var service=new AuditLogServiceImpl(audits,mock(QueryTaskMapper.class),new com.fasterxml.jackson.databind.ObjectMapper());
        var task=QueryTask.builder().id(12L).status("COMPLETED").build();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.recordCommittedAudit(task))
                .isInstanceOf(com.dataocean.common.exception.BusinessException.class);
        org.mockito.Mockito.verify(audits,org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any(QueryAuditLog.class));
    }
}
