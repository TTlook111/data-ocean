package com.dataocean.module.prompt.service.impl;

import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.prompt.entity.dto.PromptEffectivenessVO;
import com.dataocean.module.prompt.mapper.PromptTemplateMapper;
import com.dataocean.module.prompt.mapper.PromptTemplateVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptTemplateServiceImplTest {

    @Test
    void getEffectivenessAggregatesAuditLogsByPromptVersion() {
        PromptTemplateMapper templateMapper = mock(PromptTemplateMapper.class);
        PromptTemplateVersionMapper versionMapper = mock(PromptTemplateVersionMapper.class);
        QueryAuditLogMapper auditLogMapper = mock(QueryAuditLogMapper.class);
        PromptTemplateServiceImpl service = new PromptTemplateServiceImpl(
                templateMapper,
                versionMapper,
                auditLogMapper,
                new ObjectMapper()
        );

        when(auditLogMapper.selectList(any())).thenReturn(List.of(
                auditLog(
                        true,
                        1000,
                        "LIKE",
                        "[{\"templateCode\":\"sql_generation\",\"versionNo\":2},{\"templateCode\":\"chart_generation\",\"versionNo\":1}]"
                ),
                auditLog(false, 3000, "DISLIKE", "[{\"templateCode\":\"sql_generation\",\"versionNo\":2}]"),
                auditLog(true, 500, null, "[{\"templateCode\":\"sql_generation\",\"versionNo\":3}]")
        ));

        List<PromptEffectivenessVO> rows = service.getEffectiveness(30);

        PromptEffectivenessVO sqlV2 = rows.stream()
                .filter(row -> "sql_generation".equals(row.getTemplateCode()) && row.getVersionNo() == 2)
                .findFirst()
                .orElseThrow();
        assertThat(sqlV2.getTotalQueries()).isEqualTo(2);
        assertThat(sqlV2.getSuccessCount()).isEqualTo(1);
        assertThat(sqlV2.getSuccessRate()).isEqualTo(50.0);
        assertThat(sqlV2.getAvgExecutionTimeMs()).isEqualTo(2000.0);
        assertThat(sqlV2.getFeedbackCount()).isEqualTo(2);
        assertThat(sqlV2.getPositiveFeedbackCount()).isEqualTo(1);
        assertThat(sqlV2.getPositiveFeedbackRate()).isEqualTo(50.0);
    }

    private static QueryAuditLog auditLog(
            boolean success,
            int executionTimeMs,
            String feedback,
            String promptVersions
    ) {
        QueryAuditLog auditLog = new QueryAuditLog();
        auditLog.setIsSuccess(success);
        auditLog.setExecutionTimeMs(executionTimeMs);
        auditLog.setUserFeedback(feedback);
        auditLog.setPromptVersions(promptVersions);
        auditLog.setCreatedAt(LocalDateTime.now());
        return auditLog;
    }
}
