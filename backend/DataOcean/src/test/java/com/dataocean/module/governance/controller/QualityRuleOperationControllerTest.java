package com.dataocean.module.governance.controller;

import com.dataocean.common.exception.GlobalExceptionHandler;
import com.dataocean.module.governance.service.*;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 旧路径仍必须携带可靠写条件，HTTP 参数绑定不能让历史客户端绕过新协议。 */
class QualityRuleOperationControllerTest {
    private final QualityRuleService rules = mock(QualityRuleService.class);
    private final QualityRuleOperationService operations = mock(QualityRuleOperationService.class);
    private MockMvc mvc;
    private static final String CONFIGURATION = """
            {"ruleName":"协议验收", "templateCode":"COMP_TABLE_COMMENT_MISSING", "scopeType":"DATASOURCE",
             "datasourceId":5,"severity":"LOW","deductionPoints":1,"enabled":false}
            """;

    @BeforeEach
    void setup() {
        var controller = new MetadataGovernanceController(mock(QualityCheckService.class), rules, operations,
                mock(QualityIssueService.class), mock(GovernanceStatusService.class), mock(MetadataReviewService.class),
                mock(IamS1CapabilityService.class), new QualityRuleTemplateRegistry(), mock(QualityCheckBatchService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void everyLegacyWriteRejectsMissingOperationIdentityBeforeInvokingAnyMutation() throws Exception {
        mvc.perform(post("/api/admin/quality-rules").contentType("application/json").content(CONFIGURATION)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/admin/quality-rules/1").contentType("application/json").content(CONFIGURATION)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/quality-rules/1/copy")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/quality-rules/1/enabled").contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/quality-rules/1").contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/quality-rules/1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/quality-rules/1/restore")).andExpect(status().isBadRequest());
        verifyNoInteractions(rules, operations);
    }

    @Test
    void anOperationIdentityAloneCannotBypassTheExpectedVersionOnLegacyPaths() throws Exception {
        String operationId = "812f4c1b-7436-4abe-9789-14c1412f186f";
        mvc.perform(put("/api/admin/quality-rules/1").param("operationId", operationId)
                .contentType("application/json").content(CONFIGURATION)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/admin/quality-rules/1").param("operationId", operationId)
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/quality-rules/1").param("operationId", operationId)).andExpect(status().isBadRequest());
        verifyNoInteractions(rules, operations);
    }
}
