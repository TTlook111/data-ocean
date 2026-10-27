package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dataocean.module.audit.entity.po.*;
import com.dataocean.module.audit.mapper.*;
import com.dataocean.module.query.entity.po.*;
import com.dataocean.module.query.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 隔离 H2 + 真实 MyBatis 与 Spring REQUIRES_NEW 代理，不使用应用数据库。 */
class QueryTraceProjectionIntegrationTest {
    @Test void notificationRunsAfterCommitAndRollbackPublishesNoProjection() {
        Fixture f = new Fixture(); f.insert(1L, 5L);
        f.jdbc.update("UPDATE query_task SET status='PROCESSING', trace_projection_status=NULL WHERE id=1");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.registerBean(QueryTraceProjectionWorker.class, () -> new QueryTraceProjectionWorker(f.tasks, f.service));
            context.refresh();
            var transaction = new org.springframework.transaction.support.TransactionTemplate(f.transactions);
            transaction.executeWithoutResult(status -> {
                f.jdbc.update("UPDATE query_task SET status='COMPLETED', trace_projection_status='PENDING' WHERE id=1");
                context.publishEvent(new com.dataocean.module.query.event.QueryTraceCommittedEvent("task-1"));
                assertThat(f.count("query_audit_log")).isZero(); status.setRollbackOnly();
            });
            assertThat(f.count("query_audit_log")).isZero();
            transaction.executeWithoutResult(status -> {
                f.jdbc.update("UPDATE query_task SET status='COMPLETED', trace_projection_status='PENDING' WHERE id=1");
                context.publishEvent(new com.dataocean.module.query.event.QueryTraceCommittedEvent("task-1"));
                assertThat(f.count("query_audit_log")).isZero();
            });
            assertThat(f.count("query_audit_log")).isEqualTo(1);
        }
    }

    @Test void recoveryRetriesPendingTasksAndDoesNotLetOneInvalidTaskBlockOthers() {
        Fixture f = new Fixture(); f.insert(1L, 5L); f.insert(2L, 5L);
        f.jdbc.update("UPDATE query_attempt SET sql_hash='bad' WHERE task_id='task-1'");
        var worker = new QueryTraceProjectionWorker(f.tasks, f.service); worker.recover();
        assertThat(f.tasks.selectById(1L).getTraceProjectionStatus()).isEqualTo("PENDING");
        assertThat(f.tasks.selectById(2L).getTraceProjectionStatus()).isEqualTo("COMPLETED");
        f.jdbc.update("UPDATE query_attempt SET sql_hash='hash' WHERE task_id='task-1'"); worker.recover();
        assertThat(f.count("query_audit_log")).isEqualTo(2);
    }
    @Test void repeatedAndConcurrentProjectionWritesOneAuditAndNoPhysicalGraph() throws Exception {
        Fixture f = new Fixture(); f.insert(1L, 5L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> f.service.project("task-1"));
            var second = pool.submit(() -> f.service.project("task-1"));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            f.service.project("task-1");
            assertThat(f.count("query_audit_log")).isEqualTo(1);
            assertThat(f.count("query_lineage_table")).isEqualTo(1);
            assertThat(f.count("query_lineage_column")).isEqualTo(2); // SQL 使用与输出来源分别索引。
            assertThat(f.tasks.selectById(1L).getTraceProjectionStatus()).isEqualTo("COMPLETED");
        } finally { pool.shutdownNow(); }
    }

    @Test void partialFailureRollsBackAndRetainsPendingForRecovery() {
        Fixture f = new Fixture(); f.insert(1L, 5L);
        f.jdbc.execute("ALTER TABLE query_audit_log ADD CONSTRAINT reject_audit CHECK(query_task_id <> 1)");
        assertThatThrownBy(() -> f.service.project("task-1")).isInstanceOf(RuntimeException.class);
        assertThat(f.count("query_lineage_table")).isZero(); assertThat(f.count("query_lineage_column")).isZero();
        assertThat(f.tasks.selectById(1L).getTraceProjectionStatus()).isEqualTo("PENDING");
        f.jdbc.execute("ALTER TABLE query_audit_log DROP CONSTRAINT reject_audit");
        f.service.project("task-1");
        assertThat(f.count("query_audit_log")).isEqualTo(1);
    }

    @Test void wrongAttemptHashAndNonCompletedTaskCannotCreateSuccessfulTrace() {
        Fixture f = new Fixture(); f.insert(1L, 5L);
        f.jdbc.update("UPDATE query_attempt SET sql_hash='different' WHERE task_id='task-1'");
        assertThatThrownBy(() -> f.service.project("task-1")).hasMessageContaining("身份");
        assertThat(f.count("query_audit_log")).isZero();
        f.jdbc.update("UPDATE query_task SET status='CANCELLED' WHERE id=1");
        f.service.project("task-1"); assertThat(f.count("query_lineage_table")).isZero();
    }

    @Test void impactCountsDistinctProtectedTasksBeyondDetailLimitAndIsolatesDatasource() {
        Fixture f = new Fixture();
        for (long id = 1; id <= 105; id++) { f.insert(id, 5L); f.service.project("task-" + id); }
        f.insert(106L, 6L); f.service.project("task-106"); f.insert(107L, 5L);
        assertThat(f.lineage.queryTableLineage(5L, "orders")).hasSize(100);
        var tables = f.lineage.analyzeImpact(5L, "orders", null);
        assertThat(tables.getDependentQueryCount()).isEqualTo(105);
        assertThat(tables.getPendingDatasourceQueryCount()).isEqualTo(1);
        var columns = f.lineage.analyzeImpact(5L, "orders", "id");
        assertThat(columns.getDependentQueryCount()).isEqualTo(105);
        assertThat(columns.getRecentQueryTaskIds()).hasSize(10).doesNotHaveDuplicates();
    }

    static class Fixture {
        final JdbcTemplate jdbc;
        final QueryTaskMapper tasks;
        final QueryAttemptMapper attempts;
        final LineageServiceImpl lineage;
        final QueryTraceProjectionService service;
        final DataSourceTransactionManager transactions;
        final ObjectMapper json = new ObjectMapper();
        Fixture() {
            var source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:trace_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
            jdbc = new JdbcTemplate(source);
            for (Class<?> type : List.of(QueryTask.class, QueryAttempt.class, QueryAuditLog.class, QueryLineageTable.class, QueryLineageColumn.class)) create(type);
            var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.setEnvironment(new Environment("isolated-trace", new SpringManagedTransactionFactory(), source));
            for (Class<?> mapper : List.of(QueryTaskMapper.class, QueryAttemptMapper.class, QueryAuditLogMapper.class, QueryLineageTableMapper.class, QueryLineageColumnMapper.class)) config.addMapper(mapper);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            tasks = session.getMapper(QueryTaskMapper.class); attempts = session.getMapper(QueryAttemptMapper.class);
            lineage = new LineageServiceImpl(session.getMapper(QueryLineageTableMapper.class), session.getMapper(QueryLineageColumnMapper.class), tasks, json);
            var target = new QueryTraceProjectionService(tasks, attempts, new AuditLogServiceImpl(session.getMapper(QueryAuditLogMapper.class), tasks), lineage, json);
            ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            transactions = new DataSourceTransactionManager(source);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            service = (QueryTraceProjectionService) proxy.getProxy();
        }
        void create(Class<?> type) {
            var fields = new ArrayList<String>();
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String name = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
                String sql = field.getName().equals("id") ? "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY" :
                        field.getType() == Long.class ? "BIGINT" : field.getType() == Integer.class ? "INT" :
                        field.getType() == Boolean.class ? "BOOLEAN" : field.getType() == LocalDateTime.class ? "TIMESTAMP" :
                        field.getType() == java.math.BigDecimal.class ? "DECIMAL(20,6)" : "VARCHAR(100000)";
                fields.add(name + " " + sql);
            }
            jdbc.execute("CREATE TABLE " + type.getAnnotation(TableName.class).value() + "(" + String.join(",", fields) + ")");
        }
        void insert(long id, long datasource) {
            try {
                String entries = "[{\"outputColumn\":\"id\",\"sources\":[\"orders.id\"]}]";
                QueryTask task = QueryTask.builder().id(id).taskId("task-" + id).userId(7L).datasourceId(datasource)
                        .question("隔离题").status("COMPLETED").traceProjectionStatus("PENDING").iamProtocolVersion("IAM-SIMPLE-1")
                        .iamFinalProtectionStatus("FINAL_PROTECTED").activeMetadataSnapshotId(88L).permissionRevision(9L).completedAt(LocalDateTime.now())
                        .usedTables("[\"orders\"]").usedColumns("[\"orders.id\"]")
                        .iamSourceTrace(json.writeValueAsString(Map.of("attemptId", "attempt", "sqlHash", "hash", "permissionRevision", 9L, "activeMetadataSnapshotId", 88L, "entries", json.readTree(entries)))).build();
                tasks.insert(task);
                attempts.insert(QueryAttempt.builder().taskId(task.getTaskId()).attemptId("attempt").sqlHash("hash").status("PROTECTED")
                        .activeMetadataSnapshotId(88L).permissionRevision(9L).sourceTrace(entries).usedTables(task.getUsedTables()).usedColumns(task.getUsedColumns()).build());
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    }
}
