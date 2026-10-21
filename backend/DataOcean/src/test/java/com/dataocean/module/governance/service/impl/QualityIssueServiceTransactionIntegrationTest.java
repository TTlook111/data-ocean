package com.dataocean.module.governance.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.fieldtag.entity.po.FieldConfidence;
import com.dataocean.module.fieldtag.entity.po.FieldConfidenceEvent;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceEventMapper;
import com.dataocean.module.fieldtag.mapper.FieldConfidenceMapper;
import com.dataocean.module.fieldtag.service.ConfidenceCalculator;
import com.dataocean.module.fieldtag.service.impl.ConfidenceCalculatorImpl;
import com.dataocean.module.governance.entity.po.MetadataQualityIssue;
import com.dataocean.module.governance.entity.vo.IssueBatchHandleResultVO;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.service.QualityIssueService;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.resource.impl.GovernanceIssueResourceResolver;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.user.entity.po.SysUser;
import com.dataocean.module.user.mapper.UserMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 隔离 H2 + MyBatis + Spring 事务代理回归；不连接应用库。
 */
class QualityIssueServiceTransactionIntegrationTest {

    @Test
    void assignmentCannotOverwriteAStatusCommittedAfterItsRead() {
        try (Fixture fixture = new Fixture(false, null)) {
            long issueId = fixture.insertIssue(MetadataQualityIssue.STATUS_OPEN, null);
            SysUser assignee = activeUser(77L);
            TransactionTemplate requiresNew = new TransactionTemplate(fixture.transactionManager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            when(fixture.userMapper.selectById(77L)).thenAnswer(invocation -> {
                requiresNew.executeWithoutResult(status ->
                        fixture.issueService.handleIssue(issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L));
                return assignee;
            });

            fixture.issueService.assignIssue(issueId, 77L);

            MetadataQualityIssue persisted = fixture.issueMapper.selectById(issueId);
            assertThat(persisted.getStatus()).isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
            assertThat(persisted.getAssigneeId()).isEqualTo(77L);
        }
    }

    @Test
    void competingTransitionsUseDatabaseCompareAndSetAndOnlyOneCanAdjustConfidence() throws Exception {
        StatusTransitionBarrier barrier = new StatusTransitionBarrier();
        try (Fixture fixture = new Fixture(false, barrier)) {
            fixture.insertConfidence(300L, 50);
            long issueId = fixture.insertIssue(MetadataQualityIssue.STATUS_OPEN, 300L);
            AtomicReference<Throwable> winnerError = new AtomicReference<>();
            AtomicReference<Throwable> loserError = new AtomicReference<>();
            Thread winner = new Thread(() -> {
                try {
                    fixture.issueService.handleIssue(issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 10L);
                } catch (Throwable error) {
                    winnerError.set(error);
                } finally {
                    barrier.winnerCommitted.countDown();
                }
            }, "issue-status-winner");
            Thread loser = new Thread(() -> {
                try {
                    fixture.issueService.handleIssue(issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 11L);
                } catch (Throwable error) {
                    loserError.set(error);
                }
            }, "issue-status-loser");

            winner.start();
            loser.start();
            winner.join(TimeUnit.SECONDS.toMillis(10));
            loser.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(winner.isAlive()).isFalse();
            assertThat(loser.isAlive()).isFalse();
            assertThat(winnerError.get()).isNull();
            assertThat(loserError.get()).isInstanceOfSatisfying(com.dataocean.common.exception.BusinessException.class,
                    error -> assertThat(error.getCode()).isEqualTo(409));
            assertThat(fixture.issueMapper.selectById(issueId).getStatus())
                    .isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
            assertThat(fixture.confidence(300L).getScore()).isEqualTo(45);
            assertThat(fixture.eventCount()).isEqualTo(1);
        }
    }

    @Test
    void currentResolutionFieldsAreClearedOnReopenAndReplacedOnTheNextDecision() {
        try (Fixture fixture = new Fixture(false, null)) {
            MetadataQualityIssue resolved = fixture.insertIssueEntity(MetadataQualityIssue.STATUS_RESOLVED, null);
            resolved.setResolvedBy(8L);
            resolved.setResolvedAt(LocalDateTime.of(2020, 1, 2, 3, 4));
            resolved.setResolutionNote("旧解决备注");
            fixture.issueMapper.updateById(resolved);

            fixture.issueService.handleIssue(resolved.getId(), MetadataQualityIssue.STATUS_REOPENED, null, 90L);
            assertResolutionInfoCleared(fixture.issueMapper.selectById(resolved.getId()), MetadataQualityIssue.STATUS_REOPENED);

            fixture.issueService.handleIssue(resolved.getId(), MetadataQualityIssue.STATUS_CONFIRMED, null, 91L);
            assertResolutionInfoCleared(fixture.issueMapper.selectById(resolved.getId()), MetadataQualityIssue.STATUS_CONFIRMED);

            fixture.issueService.handleIssue(resolved.getId(), MetadataQualityIssue.STATUS_RESOLVED, "本轮解决备注", 92L);
            MetadataQualityIssue finalResolved = fixture.issueMapper.selectById(resolved.getId());
            assertThat(finalResolved.getStatus()).isEqualTo(MetadataQualityIssue.STATUS_RESOLVED);
            assertThat(finalResolved.getResolvedBy()).isEqualTo(92L);
            assertThat(finalResolved.getResolvedAt()).isNotNull();
            assertThat(finalResolved.getResolutionNote()).isEqualTo("本轮解决备注");
        }
    }

    @Test
    void rejectionAfterReopenClearsTheOldNoteWhenTheNewNoteIsNull() {
        try (Fixture fixture = new Fixture(false, null)) {
            MetadataQualityIssue rejected = fixture.insertIssueEntity(MetadataQualityIssue.STATUS_REJECTED, null);
            rejected.setResolvedBy(8L);
            rejected.setResolvedAt(LocalDateTime.of(2020, 1, 2, 3, 4));
            rejected.setResolutionNote("旧驳回备注");
            fixture.issueMapper.updateById(rejected);

            fixture.issueService.handleIssue(rejected.getId(), MetadataQualityIssue.STATUS_REOPENED, null, 90L);
            assertResolutionInfoCleared(fixture.issueMapper.selectById(rejected.getId()), MetadataQualityIssue.STATUS_REOPENED);

            fixture.issueService.handleIssue(rejected.getId(), MetadataQualityIssue.STATUS_REJECTED, null, 93L);
            MetadataQualityIssue finalRejected = fixture.issueMapper.selectById(rejected.getId());
            assertThat(finalRejected.getStatus()).isEqualTo(MetadataQualityIssue.STATUS_REJECTED);
            assertThat(finalRejected.getResolvedBy()).isEqualTo(93L);
            assertThat(finalRejected.getResolvedAt()).isNotNull();
            assertThat(finalRejected.getResolutionNote()).isNull();
        }
    }

    @Test
    void confidenceRunsAfterCommitAndDuplicateConfirmationDoesNotAddAnotherEvent() {
        try (Fixture fixture = new Fixture(false, null)) {
            fixture.insertConfidence(300L, 50);
            long issueId = fixture.insertIssue(MetadataQualityIssue.STATUS_OPEN, 300L);

            fixture.issueService.handleIssue(issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L);

            assertThat(fixture.issueMapper.selectById(issueId).getStatus())
                    .isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
            assertThat(fixture.confidence(300L).getScore()).isEqualTo(45);
            assertThat(fixture.eventCount()).isEqualTo(1);
            assertThat(fixture.eventsFor(300L)).extracting(FieldConfidenceEvent::getEventType)
                    .containsExactly(FieldConfidenceEvent.TYPE_GOVERNANCE_ISSUE_CONFIRMED);

            assertThatThrownBy(() -> fixture.issueService.handleIssue(
                    issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L))
                    .isInstanceOf(com.dataocean.common.exception.BusinessException.class);
            assertThat(fixture.eventCount()).isEqualTo(1);
            assertThat(fixture.confidence(300L).getScore()).isEqualTo(45);
        }
    }

    @Test
    void confidenceFailureRollsBackOnlyItsOwnTransactionAfterIssueCommit() {
        try (Fixture fixture = new Fixture(true, null)) {
            fixture.insertConfidence(300L, 50);
            long issueId = fixture.insertIssue(MetadataQualityIssue.STATUS_OPEN, 300L);

            // The service intentionally degrades the auxiliary confidence update after the issue commit.
            fixture.issueService.handleIssue(issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L);

            assertThat(fixture.issueMapper.selectById(issueId).getStatus())
                    .isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
            assertThat(fixture.confidence(300L).getScore()).isEqualTo(50);
            assertThat(fixture.eventCount()).isZero();
        }
    }

    @Test
    void mainTransactionRollbackDoesNotRunTheAfterCommitConfidenceCallback() {
        try (Fixture fixture = new Fixture(false, null)) {
            fixture.insertConfidence(300L, 50);
            long issueId = fixture.insertIssue(MetadataQualityIssue.STATUS_OPEN, 300L);
            TransactionTemplate transaction = new TransactionTemplate(fixture.transactionManager);

            transaction.executeWithoutResult(status -> {
                fixture.issueService.handleIssue(issueId, MetadataQualityIssue.STATUS_CONFIRMED, null, 99L);
                status.setRollbackOnly();
            });

            assertThat(fixture.issueMapper.selectById(issueId).getStatus()).isEqualTo(MetadataQualityIssue.STATUS_OPEN);
            assertThat(fixture.confidence(300L).getScore()).isEqualTo(50);
            assertThat(fixture.eventCount()).isZero();
        }
    }

    @Test
    void batchKeepsItsExistingPerIssueSkipSemanticsAndRunsConfidenceAfterCommit() {
        try (Fixture fixture = new Fixture(false, null)) {
            fixture.insertConfidence(300L, 50);
            fixture.insertConfidence(301L, 60);
            long openId = fixture.insertIssue(MetadataQualityIssue.STATUS_OPEN, 300L);
            long resolvedId = fixture.insertIssue(MetadataQualityIssue.STATUS_RESOLVED, 301L);
            when(fixture.issueResourceResolver.resolve(openId)).thenReturn(
                    IamS1ResolvedResource.of(IamS1ResourceType.GOVERNANCE_ISSUE, openId, 5L));
            when(fixture.issueResourceResolver.resolve(resolvedId)).thenReturn(
                    IamS1ResolvedResource.of(IamS1ResourceType.GOVERNANCE_ISSUE, resolvedId, 5L));

            IssueBatchHandleResultVO result = fixture.issueService.batchHandle(
                    List.of(openId, resolvedId), MetadataQualityIssue.STATUS_CONFIRMED, 99L);

            assertThat(result.getUpdated()).isEqualTo(1);
            assertThat(result.getSkipped()).isEqualTo(1);
            assertThat(result.getSkippedIssues()).singleElement()
                    .satisfies(skipped -> {
                        assertThat(skipped.getIssueId()).isEqualTo(resolvedId);
                        assertThat(skipped.getReason()).contains("不允许从 RESOLVED 转换到 CONFIRMED");
                    });
            assertThat(fixture.issueMapper.selectById(openId).getStatus())
                    .isEqualTo(MetadataQualityIssue.STATUS_CONFIRMED);
            assertThat(fixture.issueMapper.selectById(resolvedId).getStatus())
                    .isEqualTo(MetadataQualityIssue.STATUS_RESOLVED);
            assertThat(fixture.confidence(300L).getScore()).isEqualTo(45);
            assertThat(fixture.confidence(301L).getScore()).isEqualTo(60);
            assertThat(fixture.eventCount()).isEqualTo(1);
        }
    }

    private static void assertResolutionInfoCleared(MetadataQualityIssue issue, String status) {
        assertThat(issue.getStatus()).isEqualTo(status);
        assertThat(issue.getResolvedBy()).isNull();
        assertThat(issue.getResolvedAt()).isNull();
        assertThat(issue.getResolutionNote()).isNull();
    }

    private static SysUser activeUser(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setStatus(SysUser.STATUS_NORMAL);
        return user;
    }

    private static final class Fixture implements AutoCloseable {
        private final UnpooledDataSource dataSource;
        private final DataSourceTransactionManager transactionManager;
        private final MetadataQualityIssueMapper issueMapper;
        private final FieldConfidenceMapper confidenceMapper;
        private final FieldConfidenceEventMapper eventMapper;
        private final UserMapper userMapper = mock(UserMapper.class);
        private final GovernanceIssueResourceResolver issueResourceResolver = mock(GovernanceIssueResourceResolver.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final QualityIssueService issueService;

        private Fixture(boolean rejectGovernanceConfirmEvent, Interceptor interceptor) {
            String url = "jdbc:h2:mem:quality_issue_tx_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
            dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
            createSchema(dataSource, rejectGovernanceConfirmEvent);
            transactionManager = new DataSourceTransactionManager(dataSource);

            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("isolated-quality-issue-test",
                    new SpringManagedTransactionFactory(), dataSource));
            if (interceptor != null) configuration.addInterceptor(interceptor);
            configuration.addMapper(MetadataQualityIssueMapper.class);
            configuration.addMapper(FieldConfidenceMapper.class);
            configuration.addMapper(FieldConfidenceEventMapper.class);
            SqlSessionTemplate sqlSession = new SqlSessionTemplate(
                    new MybatisSqlSessionFactoryBuilder().build(configuration));
            issueMapper = sqlSession.getMapper(MetadataQualityIssueMapper.class);
            confidenceMapper = sqlSession.getMapper(FieldConfidenceMapper.class);
            eventMapper = sqlSession.getMapper(FieldConfidenceEventMapper.class);

            ConfidenceCalculator calculator = transactionProxy(
                    new ConfidenceCalculatorImpl(confidenceMapper, eventMapper), ConfidenceCalculator.class);
            QualityIssueServiceImpl service = new QualityIssueServiceImpl(issueMapper, userMapper,
                    mock(DatasourceMapper.class), mock(MetadataSnapshotMapper.class), calculator,
                    adminGuard, issueResourceResolver);
            issueService = transactionProxy(service, QualityIssueService.class);
        }

        private void createSchema(DataSource source, boolean rejectGovernanceConfirmEvent) {
            String eventConstraint = rejectGovernanceConfirmEvent
                    ? ", CONSTRAINT reject_governance_confirm CHECK (event_type <> 'GOVERNANCE_ISSUE_CONFIRMED')" : "";
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE metadata_quality_issue ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, snapshot_id BIGINT NOT NULL, datasource_id BIGINT NOT NULL, "
                        + "rule_id BIGINT, check_batch_id BIGINT, dimension VARCHAR(20), severity VARCHAR(10), "
                        + "table_name VARCHAR(200), column_name VARCHAR(200), issue_description VARCHAR(1000), "
                        + "suggestion VARCHAR(1000), status VARCHAR(20) NOT NULL, assignee_id BIGINT, "
                        + "resolved_by BIGINT, resolved_at TIMESTAMP, resolution_note VARCHAR(500), "
                        + "created_at TIMESTAMP, updated_at TIMESTAMP, column_meta_id BIGINT)");
                statement.execute("CREATE TABLE field_confidence ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, column_meta_id BIGINT NOT NULL UNIQUE, "
                        + "score INT NOT NULL, level VARCHAR(10) NOT NULL, reason VARCHAR(500), "
                        + "updated_at TIMESTAMP, updated_by BIGINT)");
                statement.execute("CREATE TABLE field_confidence_event ("
                        + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, column_meta_id BIGINT NOT NULL, "
                        + "delta_score INT NOT NULL, event_type VARCHAR(50) NOT NULL, source_query_id BIGINT, "
                        + "operator_id BIGINT, created_at TIMESTAMP" + eventConstraint + ")");
            } catch (Exception exception) {
                throw new IllegalStateException("创建隔离 H2 治理测试表失败", exception);
            }
        }

        private <T> T transactionProxy(T target, Class<T> contract) {
            ProxyFactory factory = new ProxyFactory(target);
            factory.setInterfaces(contract);
            factory.addAdvice(new TransactionInterceptor(transactionManager,
                    new AnnotationTransactionAttributeSource()));
            return contract.cast(factory.getProxy());
        }

        long insertIssue(String status, Long columnMetaId) {
            return insertIssueEntity(status, columnMetaId).getId();
        }

        MetadataQualityIssue insertIssueEntity(String status, Long columnMetaId) {
            MetadataQualityIssue issue = new MetadataQualityIssue();
            issue.setSnapshotId(8L);
            issue.setDatasourceId(5L);
            issue.setRuleId(1L);
            issue.setDimension("COMPLETENESS");
            issue.setSeverity("MEDIUM");
            issue.setTableName("orders");
            issue.setIssueDescription("isolated transaction test");
            issue.setStatus(status);
            issue.setColumnMetaId(columnMetaId);
            issue.setCreatedAt(LocalDateTime.now());
            issue.setUpdatedAt(LocalDateTime.now());
            issueMapper.insert(issue);
            return issue;
        }

        void insertConfidence(Long columnMetaId, int score) {
            FieldConfidence confidence = new FieldConfidence();
            confidence.setColumnMetaId(columnMetaId);
            confidence.setScore(score);
            confidence.setLevel(FieldConfidence.calculateLevel(score));
            confidence.setReason("test baseline");
            confidence.setUpdatedAt(LocalDateTime.now());
            confidenceMapper.insert(confidence);
        }

        FieldConfidence confidence(Long columnMetaId) {
            return confidenceMapper.selectOne(new QueryWrapper<FieldConfidence>().eq("column_meta_id", columnMetaId));
        }

        long eventCount() {
            return eventMapper.selectCount(new QueryWrapper<>())
                    .longValue();
        }

        List<FieldConfidenceEvent> eventsFor(Long columnMetaId) {
            return eventMapper.selectList(new QueryWrapper<FieldConfidenceEvent>().eq("column_meta_id", columnMetaId));
        }

        @Override
        public void close() {
            // H2 databases use unique in-memory names and are discarded when the test JVM exits.
        }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query", args = {
                    MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
    })
    private static final class StatusTransitionBarrier implements Interceptor {
        private final CountDownLatch bothRowsRead = new CountDownLatch(2);
        private final CountDownLatch winnerCommitted = new CountDownLatch(1);

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().equals(MetadataQualityIssueMapper.class.getName() + ".selectById")) {
                Object issue = invocation.proceed();
                bothRowsRead.countDown();
                if (!bothRowsRead.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待两个事务读取 OPEN 状态超时");
                }
                return issue;
            }
            if (statement.getId().equals(MetadataQualityIssueMapper.class.getName() + ".update")
                    && Thread.currentThread().getName().endsWith("status-loser")) {
                if (!winnerCommitted.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待首个状态事务提交超时");
                }
            }
            return invocation.proceed();
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }
    }
}
