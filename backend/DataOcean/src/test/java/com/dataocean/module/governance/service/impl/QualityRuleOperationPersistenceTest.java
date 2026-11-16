package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.dto.QualityRuleDTO;
import com.dataocean.module.governance.entity.dto.QualityRuleOperationDTO;
import com.dataocean.module.governance.entity.vo.QualityRuleOperationVO;
import com.dataocean.module.governance.mapper.MetadataQualityRuleMapper;
import com.dataocean.module.governance.service.QualityRuleOperationService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** 真实 Spring 事务与 H2 并发连接；验证可靠写协议，不冒充 MySQL 迁移验收。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quality_rule_operations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
@Sql("/quality-rule-operations-test.sql")
class QualityRuleOperationPersistenceTest {
    @Autowired QualityRuleOperationService operations;
    @Autowired MetadataQualityRuleMapper rules;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean IamS1AuthorizationResolver authorization;
    @SpyBean QualityRuleServiceImpl mutations;

    @BeforeEach
    void authorizeFixtures() {
        when(authorization.isSystemAdmin(1L)).thenReturn(true);
        when(authorization.canManageDatasource(1L, "governance:rule:manage", 5L)).thenReturn(true);
        when(authorization.canManageDatasource(1L, "governance:rule:manage", 6L)).thenReturn(true);
        when(authorization.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(true);
    }

    @Test
    void lostCreateResponseCanBeReadAndReplayedWithoutAnotherRuleOrRevision() {
        QualityRuleOperationDTO create = create(5L);
        QualityRuleOperationVO first = operations.execute(create, 9L);
        QualityRuleOperationVO replay = operations.execute(create, 9L);

        assertThat(first.getOutcome()).isEqualTo("APPLIED");
        assertThat(replay).isEqualTo(first);
        assertThat(operations.get(create.getOperationId(), 9L)).isEqualTo(first);
        assertCounts(1, 1, 1);
        // 回执中只含操作定位、结果版本和状态，不保存配置正文或密码。
        assertThat(jdbc.queryForMap("SELECT * FROM quality_rule_operation"))
                .doesNotContainKeys("configuration", "result_json", "request_json");
    }

    @Test
    void staleEditorIsPersistentlyRejectedWithoutOverwritingTheNewConfiguration() {
        long id = operations.execute(create(5L), 9L).getRuleId();
        QualityRuleOperationDTO winner = command("UPDATE", id, 1);
        winner.setConfiguration(configuration(5L, "先保存的配置"));
        assertThat(operations.execute(winner, 9L).getConfigVersion()).isEqualTo(2);
        QualityRuleOperationDTO stale = command("UPDATE", id, 1);
        stale.setConfiguration(configuration(5L, "迟到的旧草稿"));

        QualityRuleOperationVO rejected = operations.execute(stale, 9L);

        assertThat(rejected.getOutcome()).isEqualTo("CONFLICT");
        assertThat(rejected.getErrorCode()).isEqualTo(409);
        assertThat(operations.execute(stale, 9L)).isEqualTo(rejected);
        assertThat(rules.selectById(id).getRuleName()).isEqualTo("先保存的配置");
        assertThat(rules.selectById(id).getConfigVersion()).isEqualTo(2);
        assertCounts(1, 2, 3);
    }

    @Test
    void statusWritesShareTheConfigurationVersionAndReplayOnlyTheirOriginalReceipt() {
        long id = operations.execute(create(5L), 9L).getRuleId();
        int version = 1;
        for (String action : List.of("ENABLE", "DISABLE", "DELETE", "RESTORE")) {
            QualityRuleOperationDTO change = command(action, id, version);
            QualityRuleOperationVO first = operations.execute(change, 9L);
            assertThat(first.getOutcome()).as(action).isEqualTo("APPLIED");
            assertThat(first.getConfigVersion()).isEqualTo(++version);
            assertThat(operations.execute(change, 9L)).isEqualTo(first);
        }
        assertThat(rules.selectById(id).getEnabled()).isZero();
        assertThat(rules.selectById(id).getDeletedAt()).isNull();
        assertCounts(1, 5, 5);
    }

    @Test
    void copyChecksSourceVersionAndDuplicateOperationCreatesOneDisabledCopy() {
        long id = operations.execute(create(5L), 9L).getRuleId();
        assertThat(operations.execute(command("COPY", id, 2), 9L).getOutcome()).isEqualTo("CONFLICT");
        QualityRuleOperationDTO copy = command("COPY", id, 1);
        QualityRuleOperationVO first = operations.execute(copy, 9L);
        assertThat(first.getOutcome()).isEqualTo("APPLIED");
        assertThat(first.getRuleId()).isNotEqualTo(id);
        assertThat(rules.selectById(first.getRuleId()).getEnabled()).isZero();
        assertThat(operations.execute(copy, 9L)).isEqualTo(first);
        assertCounts(2, 2, 3);
    }

    @Test
    void sameOperationIdCannotBeReusedWithDifferentConfigurationOrAction() {
        QualityRuleOperationDTO create = create(5L);
        operations.execute(create, 9L);
        create.getConfiguration().setRuleName("另一个意图");
        assertThatThrownBy(() -> operations.execute(create, 9L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        assertCounts(1, 1, 1);
    }

    @Test
    void missingVersionAndInvalidParametersAreKnownRejectionsWithoutWrites() {
        long id = operations.execute(create(5L), 9L).getRuleId();
        QualityRuleOperationDTO missing = command("DISABLE", id, null);
        assertThat(operations.execute(missing, 9L).getErrorCode()).isEqualTo(400);
        QualityRuleOperationDTO invalid = create(5L);
        invalid.getConfiguration().setTemplateCode("DATA_NULL_RATE_HIGH");
        invalid.getConfiguration().setParameters(Map.of("threshold", 10));
        assertThat(operations.execute(invalid, 9L).getOutcome()).isEqualTo("REJECTED");
        assertCounts(1, 1, 3);
    }

    @Test
    void unknownIdCanBeFencedBeforeALateOriginalRequestArrives() {
        QualityRuleOperationDTO late = create(5L);
        assertThat(operations.get(late.getOperationId(), 9L).getOutcome()).isEqualTo("NOT_FOUND");
        QualityRuleOperationVO fence = operations.resolve(late.getOperationId(), 9L);
        assertThat(fence.getOutcome()).isEqualTo("REJECTED");
        assertThat(operations.execute(late, 9L)).isEqualTo(fence);
        assertThat(operations.resolve(late.getOperationId(), 9L)).isEqualTo(fence);
        assertCounts(0, 0, 1);
    }

    @Test
    void resolveWaitsForAnInFlightCommitInsteadOfDeclaringItNotSubmitted() throws Exception {
        QualityRuleOperationDTO create = create(5L);
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch resolving = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var submit = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                QualityRuleOperationVO result = operations.execute(create, 9L);
                written.countDown();
                await(allowCommit);
                return result;
            }));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(operations.get(create.getOperationId(), 9L).getOutcome()).isEqualTo("NOT_FOUND");
            var resolve = pool.submit(() -> {
                resolving.countDown();
                return operations.resolve(create.getOperationId(), 9L);
            });
            assertThat(resolving.await(5, TimeUnit.SECONDS)).isTrue();
            allowCommit.countDown();
            QualityRuleOperationVO applied = submit.get(10, TimeUnit.SECONDS);
            assertThat(resolve.get(10, TimeUnit.SECONDS)).isEqualTo(applied);
            assertThat(applied.getOutcome()).isEqualTo("APPLIED");
            assertCounts(1, 1, 1);
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousSameIdCreatesOnlyOneRuleAndDifferentIdsCompeteOnVersion() throws Exception {
        QualityRuleOperationDTO create = create(5L);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { await(start); return operations.execute(create, 9L); });
            var b = pool.submit(() -> { await(start); return operations.execute(create, 9L); });
            start.countDown();
            QualityRuleOperationVO created = a.get(10, TimeUnit.SECONDS);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(created);
            assertCounts(1, 1, 1);

            QualityRuleOperationDTO editA = command("UPDATE", created.getRuleId(), 1);
            editA.setConfiguration(configuration(5L, "并发 A"));
            QualityRuleOperationDTO editB = command("UPDATE", created.getRuleId(), 1);
            editB.setConfiguration(configuration(5L, "并发 B"));
            CountDownLatch edits = new CountDownLatch(1);
            var c = pool.submit(() -> { await(edits); return operations.execute(editA, 9L); });
            var d = pool.submit(() -> { await(edits); return operations.execute(editB, 9L); });
            edits.countDown();
            assertThat(List.of(c.get(10, TimeUnit.SECONDS).getOutcome(), d.get(10, TimeUnit.SECONDS).getOutcome()))
                    .containsExactlyInAnyOrder("APPLIED", "CONFLICT");
            assertCounts(1, 2, 3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resolveHoldingTheUniqueKeyPreventsAnOriginalRequestFromCommittingLater() throws Exception {
        QualityRuleOperationDTO late = create(5L);
        CountDownLatch fenced = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var resolving = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                QualityRuleOperationVO result = operations.resolve(late.getOperationId(), 9L);
                fenced.countDown();
                await(allowCommit);
                return result;
            }));
            assertThat(fenced.await(5, TimeUnit.SECONDS)).isTrue();
            var submitting = pool.submit(() -> operations.execute(late, 9L));
            allowCommit.countDown();
            QualityRuleOperationVO rejection = resolving.get(10, TimeUnit.SECONDS);
            assertThat(submitting.get(10, TimeUnit.SECONDS)).isEqualTo(rejection);
            assertThat(rejection.getOutcome()).isEqualTo("REJECTED");
            assertCounts(0, 0, 1);
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void persistenceFailureRollsBackRuleRevisionAndReceiptSoTheSameIntentCanRetry() {
        QualityRuleOperationDTO create = create(5L);
        // 让修订持久化在规则 INSERT 后失败，证明没有只提交规则而缺回执的中间状态。
        jdbc.execute("ALTER TABLE metadata_quality_rule_revision ADD CONSTRAINT reject_create CHECK(action <> 'CREATE')");
        assertThatThrownBy(() -> operations.execute(create, 9L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertCounts(0, 0, 0);
        jdbc.execute("ALTER TABLE metadata_quality_rule_revision DROP CONSTRAINT reject_create");
        assertThat(operations.execute(create, 9L).getOutcome()).isEqualTo("APPLIED");
        assertCounts(1, 1, 1);
    }

    @Test
    void businessRejectionAfterMutationRollsBackTheSavepointButCommitsOnlyTheRefusal() {
        // 注入写后业务拒绝，验证外层捕获不会意外提交已写的规则和修订。
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new BusinessException(409, "测试写后业务拒绝");
        }).when(mutations).createRule(any(QualityRuleDTO.class), eq(9L));
        QualityRuleOperationDTO create = create(5L);
        QualityRuleOperationVO rejected = operations.execute(create, 9L);
        assertThat(rejected.getOutcome()).isEqualTo("CONFLICT");
        assertThat(operations.get(create.getOperationId(), 9L)).isEqualTo(rejected);
        assertCounts(0, 0, 1);
    }

    @Test
    void receiptPersistenceFailureRollsBackEvenTheAlreadySuccessfulRuleAndRevision() {
        // PENDING 占位允许插入，但最终成功回执失败；整个事务必须回滚，不能留下无回执成功。
        jdbc.execute("ALTER TABLE quality_rule_operation ADD CONSTRAINT reject_applied CHECK(outcome <> 'APPLIED')");
        QualityRuleOperationDTO create = create(5L);
        assertThatThrownBy(() -> operations.execute(create, 9L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertCounts(0, 0, 0);
        jdbc.execute("ALTER TABLE quality_rule_operation DROP CONSTRAINT reject_applied");
        assertThat(operations.execute(create, 9L).getOutcome()).isEqualTo("APPLIED");
    }

    @Test
    void concurrentDifferentRulesCannotBothEnableTheSameTargetEvenAtReadCommitted() throws Exception {
        QualityRuleOperationDTO enabledA = create(5L);
        enabledA.getConfiguration().setEnabled(true);
        QualityRuleOperationDTO enabledB = create(5L);
        enabledB.getConfiguration().setEnabled(true);
        var created = concurrentlyAtReadCommitted(enabledA, enabledB);
        assertThat(created).extracting(QualityRuleOperationVO::getOutcome)
                .containsExactlyInAnyOrder("APPLIED", "CONFLICT");
        long initialId = created.stream().filter(row -> "APPLIED".equals(row.getOutcome())).findFirst().orElseThrow().getRuleId();
        operations.execute(command("DISABLE", initialId, 1), 9L);
        long idA = operations.execute(create(5L), 9L).getRuleId();
        long idB = operations.execute(create(5L), 9L).getRuleId();

        var enabled = concurrentlyAtReadCommitted(command("ENABLE", idA, 1), command("ENABLE", idB, 1));

        assertThat(enabled).extracting(QualityRuleOperationVO::getOutcome)
                .containsExactlyInAnyOrder("APPLIED", "CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metadata_quality_rule WHERE enabled = 1", Integer.class)).isEqualTo(1);
        assertCounts(3, 5, 7);
    }

    @Test
    void receiptIsActorBoundAndRechecksOriginalTargetAndCurrentScopeAfterRevocation() {
        QualityRuleOperationDTO create = create(5L);
        long id = operations.execute(create, 9L).getRuleId();
        assertThat(operations.get(create.getOperationId(), 1L).getOutcome()).isEqualTo("NOT_FOUND");
        when(authorization.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(false);
        assertForbidden(() -> operations.get(create.getOperationId(), 9L));
        assertForbidden(() -> operations.execute(create, 9L));
        when(authorization.canManageDatasource(9L, "governance:rule:manage", 5L)).thenReturn(true);

        QualityRuleOperationDTO forbiddenMove = command("UPDATE", id, 1);
        forbiddenMove.setConfiguration(configuration(6L, "无权迁移"));
        assertThat(operations.execute(forbiddenMove, 9L).getErrorCode()).isEqualTo(403);
        assertThat(rules.selectById(id).getDatasourceId()).isEqualTo(5L);

        QualityRuleOperationDTO move = command("UPDATE", id, 1);
        move.setConfiguration(configuration(6L, "管理员迁移"));
        assertThat(operations.execute(move, 1L).getOutcome()).isEqualTo("APPLIED");
        assertForbidden(() -> operations.get(create.getOperationId(), 9L));
        assertForbidden(() -> operations.resolve(create.getOperationId(), 9L));
    }

    private void assertCounts(int rules, int revisions, int receipts) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metadata_quality_rule", Integer.class)).isEqualTo(rules);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM metadata_quality_rule_revision", Integer.class)).isEqualTo(revisions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quality_rule_operation", Integer.class)).isEqualTo(receipts);
    }

    /** 使用 READ_COMMITTED 排除依赖间隙锁的偶然通过，互斥必须由领域锁保证。 */
    private List<QualityRuleOperationVO> concurrentlyAtReadCommitted(QualityRuleOperationDTO a,
                                                                     QualityRuleOperationDTO b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { await(start); return atReadCommitted(a); });
            var second = pool.submit(() -> { await(start); return atReadCommitted(b); });
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private QualityRuleOperationVO atReadCommitted(QualityRuleOperationDTO request) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> operations.execute(request, 9L));
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(403));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试等待超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static QualityRuleOperationDTO create(Long datasourceId) {
        QualityRuleOperationDTO result = command("CREATE", null, null);
        result.setConfiguration(configuration(datasourceId, "规则操作协议测试"));
        return result;
    }

    private static QualityRuleOperationDTO command(String action, Long id, Integer version) {
        QualityRuleOperationDTO request = new QualityRuleOperationDTO();
        request.setOperationId(UUID.randomUUID().toString());
        request.setAction(action);
        request.setRuleId(id);
        request.setExpectedVersion(version);
        return request;
    }

    private static QualityRuleDTO configuration(Long datasourceId, String name) {
        QualityRuleDTO result = new QualityRuleDTO();
        result.setRuleName(name);
        result.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        result.setScopeType(datasourceId == null ? "GLOBAL" : "DATASOURCE");
        result.setDatasourceId(datasourceId);
        result.setSeverity("MEDIUM");
        result.setDeductionPoints(BigDecimal.ONE);
        result.setEnabled(false);
        result.setParameters(Map.of());
        return result;
    }
}
