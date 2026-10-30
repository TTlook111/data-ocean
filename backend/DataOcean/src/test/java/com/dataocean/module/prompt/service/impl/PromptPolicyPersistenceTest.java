package com.dataocean.module.prompt.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.module.prompt.entity.dto.PromptTemplateUpdateDTO;
import com.dataocean.module.prompt.entity.dto.PromptInvocationDTO;
import com.dataocean.module.prompt.entity.query.PromptInvocationQuery;
import com.dataocean.module.prompt.service.PromptTemplateService;
import com.dataocean.module.prompt.service.PromptInvocationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 独立 H2 + 真实 Spring 事务代理，不连接应用库，不证明 MySQL 迁移通过。 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:prompt_policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
class PromptPolicyPersistenceTest {
    @Autowired PromptTemplateService service;
    @Autowired PromptInvocationService invocations;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach void setup() {
        jdbc.execute("DROP TABLE IF EXISTS prompt_template_version");
        jdbc.execute("DROP TABLE IF EXISTS prompt_template");
        jdbc.execute("DROP TABLE IF EXISTS prompt_invocation");
        jdbc.execute("CREATE TABLE prompt_template(id BIGINT AUTO_INCREMENT PRIMARY KEY, template_code VARCHAR(50) UNIQUE, template_name VARCHAR(100), scenario VARCHAR(50), content CLOB, current_version INT, enabled BOOLEAN, version INT DEFAULT 0, status VARCHAR(30), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE prompt_template_version(id BIGINT AUTO_INCREMENT PRIMARY KEY, template_id BIGINT, version_no INT, content CLOB, change_summary VARCHAR(500), is_active BOOLEAN, status VARCHAR(30), created_by BIGINT, created_at TIMESTAMP, UNIQUE(template_id,version_no))");
        jdbc.update("INSERT INTO prompt_template(id,template_code,template_name,scenario,content,current_version,enabled,status) VALUES(1,'field_semantic','字段解释','知识治理','旧策略 {{input}}',1,true,'APPROVED')");
        jdbc.update("INSERT INTO prompt_template_version(template_id,version_no,content,is_active,status) VALUES(1,1,'旧策略 {{input}}',true,'APPROVED')");
        jdbc.execute("CREATE TABLE prompt_invocation(id BIGINT AUTO_INCREMENT PRIMARY KEY, invocation_id VARCHAR(64) UNIQUE, task_id VARCHAR(100), scene_code VARCHAR(50), version_no INT, source VARCHAR(16), contract_version VARCHAR(20), content_hash VARCHAR(64), fallback_reason VARCHAR(32), outcome VARCHAR(32), duration_ms BIGINT, created_at TIMESTAMP)");
        var user = new LoginUser(1L, "tester", "password", "测试", List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private PromptTemplateUpdateDTO draft(String content) {
        var dto = new PromptTemplateUpdateDTO(); dto.setContent(content); return dto;
    }

    private com.dataocean.module.prompt.entity.dto.PromptVersionActionDTO action(int version) {
        return action(version, "测试备注");
    }
    private com.dataocean.module.prompt.entity.dto.PromptVersionActionDTO action(int version, String note) {
        var request = new com.dataocean.module.prompt.entity.dto.PromptVersionActionDTO();
        request.setExpectedVersionNo(version); request.setChangeSummary(note); request.setRejectReason(note);
        return request;
    }

    @Test void draftAndRejectionNeverReplaceApprovedRuntimeVersion() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        var saved = service.updateTemplate("field_semantic", draft("新策略 {{input}}"));
        assertThat(saved.getCurrentVersion()).isEqualTo(2);
        assertThat(saved.getActiveVersion()).isEqualTo(1);
        assertThat(saved.getActiveContent()).isEqualTo("旧策略 {{input}}");
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(1);
        service.submitForReview("field_semantic", action(2));
        service.reject("field_semantic", action(2, "补充依据"));
        assertThat(service.getActiveContent("field_semantic")).isEqualTo("旧策略 {{input}}");
        // 草稿工作流不再阻止独立控制已经批准的线上版本。
        service.setEnabled("field_semantic", false);
        assertThatThrownBy(() -> service.getActiveTemplate("field_semantic")).isInstanceOf(BusinessException.class);
        service.setEnabled("field_semantic", true);
        assertThat(service.getActiveContent("field_semantic")).isEqualTo("旧策略 {{input}}");
    }

    @Test void reviewerOfVersionTwoCannotApproveLaterPendingVersionThree() {
        service.updateTemplate("field_semantic", draft("待审核 v2 {{input}}"));
        service.submitForReview("field_semantic", action(2));
        var observed = service.getTemplate("field_semantic");
        assertThat(observed.getCurrentVersion()).isEqualTo(2);
        service.reject("field_semantic", action(2, "重新起草"));
        service.updateTemplate("field_semantic", draft("另一个内容 v3 {{input}}"));
        service.submitForReview("field_semantic", action(3));
        assertThatThrownBy(() -> service.approve("field_semantic", action(observed.getCurrentVersion(), "审核 v2")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reject("field_semantic", action(observed.getCurrentVersion(), "驳回旧 v2")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.approve("field_semantic", null)).isInstanceOf(BusinessException.class);
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(1);
        assertThat(service.getTemplate("field_semantic").getCurrentVersion()).isEqualTo(3);
        assertThat(service.getTemplate("field_semantic").getStatus()).isEqualTo("PENDING_REVIEW");
        service.approve("field_semantic", action(3));
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(3);
    }

    @Test void staleSubmitDoesNotSubmitANewerDraft() {
        service.updateTemplate("field_semantic", draft("草稿 v2 {{input}}"));
        service.updateTemplate("field_semantic", draft("草稿 v3 {{input}}"));
        assertThatThrownBy(() -> service.submitForReview("field_semantic", action(2))).isInstanceOf(BusinessException.class);
        assertThat(service.getTemplate("field_semantic").getStatus()).isEqualTo("DRAFT");
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(1);
        service.submitForReview("field_semantic", action(3));
        assertThat(service.getTemplate("field_semantic").getStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test void failedTransactionRollsBackBothVersionActivationAndMainPointer() {
        service.updateTemplate("field_semantic", draft("待审 v2 {{input}}"));
        service.submitForReview("field_semantic", action(2));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.execute(status -> {
            service.approve("field_semantic", action(2));
            throw new BusinessException(409, "模拟同一事务后续失败");
        })).isInstanceOf(BusinessException.class);
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(1);
        assertThat(service.getTemplate("field_semantic").getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(jdbc.queryForObject("SELECT version_no FROM prompt_template_version WHERE is_active=true", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM prompt_template_version WHERE version_no=2", String.class)).isEqualTo("PENDING_REVIEW");
    }

    @Test void dynamicVariablesCannotEnterSystemRole() {
        String policy = "{\"formatVersion\":1,\"system\":\"{{input}}\",\"user\":\"只返回 JSON\"}";
        assertThatThrownBy(() -> service.validatePolicy("field_semantic", policy)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.updateTemplate("field_semantic", draft(policy))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prompt_template_version", Integer.class)).isEqualTo(1);
    }

    @Test void invalidJsonAndOverflowVersionAreRejectedBeforePreviewSaveAndActivation() {
        String valid = "{\"formatVersion\":1,\"system\":\"角色\",\"user\":\"{{input}}\"}";
        for (String invalid : List.of(valid + " true", valid + " {}", valid.replace(":1,", ":4294967297,"))) {
            assertThatThrownBy(() -> service.validatePolicy("field_semantic", invalid)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.updateTemplate("field_semantic", draft(invalid))).isInstanceOf(BusinessException.class);
        }
        service.updateTemplate("field_semantic", draft(valid));
        service.submitForReview("field_semantic", action(2));
        jdbc.update("UPDATE prompt_template_version SET content=? WHERE version_no=2", valid + " true");
        assertThatThrownBy(() -> service.approve("field_semantic", action(2))).isInstanceOf(BusinessException.class);
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(1);
        assertThat(service.getTemplate("field_semantic").getStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test void approvalAtomicallySwitchesActiveVersionAndRollbackOnlyCreatesDraft() {
        service.updateTemplate("field_semantic", draft("新策略 {{input}}"));
        service.submitForReview("field_semantic", action(2));
        service.approve("field_semantic", action(2, "核对通过"));
        assertThat(service.getActiveTemplate("field_semantic").getCurrentVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prompt_template_version WHERE is_active=true", Integer.class)).isEqualTo(1);
        var rollback = new com.dataocean.module.prompt.entity.dto.PromptRollbackDTO(); rollback.setTargetVersionNo(1);
        var result = service.rollback("field_semantic", rollback);
        assertThat(result.getCurrentVersion()).isEqualTo(3);
        assertThat(result.getActiveVersion()).isEqualTo(2);
        assertThat(service.getActiveContent("field_semantic")).isEqualTo("新策略 {{input}}");
    }

    @Test void invalidContractAndRetiredSceneCannotWriteVersions() {
        assertThatThrownBy(() -> service.updateTemplate("field_semantic", draft("{{unknown}}"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.updateTemplate("intent_recognition", draft("{{input}}"))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM prompt_template_version", Integer.class)).isEqualTo(1);
        assertThat(service.validatePolicy("field_semantic", "{{input}} JSON 示例：{\"body\":{\"x\":\"y\"}}").userPreview()).contains("合成测试变量：input");
        assertThatThrownBy(() -> service.validatePolicy("field_semantic", "{% for x in input %}{{input}}{% endfor %}")).isInstanceOf(BusinessException.class);
    }

    @Test void tamperedPendingContentFailsApprovalWithoutSwitchingPointer() {
        service.updateTemplate("field_semantic", draft("新策略 {{input}}"));
        service.submitForReview("field_semantic", action(2));
        jdbc.update("UPDATE prompt_template_version SET content='缺少输入' WHERE version_no=2");
        assertThatThrownBy(() -> service.approve("field_semantic", action(2, ""))).isInstanceOf(BusinessException.class);
        assertThat(service.getActiveContent("field_semantic")).isEqualTo("旧策略 {{input}}");
        assertThat(jdbc.queryForObject("SELECT status FROM prompt_template WHERE id=1", String.class)).isEqualTo("PENDING_REVIEW");
    }

    @Test void receiptIsIdempotentAndSourceVersionContractCannotBeForged() {
        var dto = new PromptInvocationDTO();
        dto.setInvocationId("receipt-1"); dto.setSceneCode("field_semantic"); dto.setVersionNo(0);
        dto.setSource("BUILTIN"); dto.setContractVersion("1"); dto.setContentHash("a".repeat(64));
        dto.setFallbackReason("DISABLED"); dto.setOutcome("MODEL_RETURNED"); dto.setDurationMs(12L);
        invocations.record(dto); invocations.record(dto);
        var query = new PromptInvocationQuery(); query.setSceneCode("field_semantic");
        assertThat(invocations.list(query).getTotal()).isEqualTo(1);
        dto.setSource("MANAGED");
        assertThatThrownBy(() -> invocations.record(dto)).isInstanceOf(BusinessException.class);
    }

    @Test void migrationAddsOnlyUnapprovedDraftsAndPreservesExistingApprovedContent() throws Exception {
        jdbc.execute("DROP TABLE prompt_invocation");
        try (var connection = jdbc.getDataSource().getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                new org.springframework.core.io.ClassPathResource("db/migration/V72__prompt_scene_invocations.sql"));
        }
        var added = service.getTemplate("schema_linking");
        assertThat(added.getActiveVersion()).isZero();
        assertThat(added.getCurrentVersion()).isEqualTo(1);
        assertThat(added.getStatus()).isEqualTo("DRAFT");
        assertThat(added.getEnabled()).isFalse();
        assertThat(service.validatePolicy("schema_linking", added.getContent()).userPreview()).contains("合成测试变量");
        assertThat(service.getActiveContent("field_semantic")).isEqualTo("旧策略 {{input}}");
    }
}
