package com.dataocean.module.versioning.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.versioning.service.SnapshotPublishService;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
import com.dataocean.module.versioning.service.SnapshotAuditLogService;
import com.dataocean.module.versioning.event.SnapshotPublishedEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** 两个连接在同一来源交错提交，保留真实门禁聚合、状态更新与事务隔离。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:release_competition;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DELETE FROM datasource WHERE id=901",
        "INSERT INTO datasource(id,name,deleted) VALUES(901,'并发测试源',0)",
        "DROP TABLE IF EXISTS metadata_snapshot",
        "CREATE TABLE metadata_snapshot(id BIGINT PRIMARY KEY,datasource_id BIGINT,snapshot_version INT,schema_hash VARCHAR(64),status VARCHAR(30),table_count INT,column_count INT,total_rows_estimate BIGINT,quality_score DECIMAL(5,2),sync_task_id BIGINT,reviewed_by BIGINT,reviewed_at TIMESTAMP,published_at TIMESTAMP,entity_sync_status VARCHAR(30),entity_sync_error VARCHAR(500),entity_synced_at TIMESTAMP,expired_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)",
        "INSERT INTO metadata_snapshot(id,datasource_id,status) VALUES(70,901,'PUBLISHED'),(80,901,'APPROVED'),(81,901,'APPROVED')",
        "DROP TABLE IF EXISTS metadata_quality_issue",
        "CREATE TABLE metadata_quality_issue(id BIGINT AUTO_INCREMENT PRIMARY KEY,snapshot_id BIGINT,issue_domain VARCHAR(16),severity VARCHAR(16),status VARCHAR(20))",
        "DROP TABLE IF EXISTS db_table_meta",
        "CREATE TABLE db_table_meta(id BIGINT PRIMARY KEY,snapshot_id BIGINT,governance_status VARCHAR(20))",
        "INSERT INTO db_table_meta VALUES(1,80,'NORMAL'),(2,81,'NORMAL')",
        "DROP TABLE IF EXISTS quality_check_batch",
        "CREATE TABLE quality_check_batch(id BIGINT PRIMARY KEY,snapshot_id BIGINT,datasource_id BIGINT,operation_id VARCHAR(36),request_hash VARCHAR(64))"
})
class SnapshotReleaseConcurrencyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SnapshotPublishService publisher;
    @Autowired SnapshotReleaseGateService gate;
    @MockBean SnapshotAuditLogService audit;
    @MockBean SnapshotPublishedEventListener events;

    @Test
    void replacingThenRevokingPreservesOriginalPointerPreconditionsAndReviewerIdentity() {
        jdbc.update("UPDATE metadata_snapshot SET reviewed_by=6 WHERE id=80");
        publisher.publishSnapshot(80L, "APPROVED", 70L, 7L);
        assertThat(jdbc.queryForObject("SELECT status FROM metadata_snapshot WHERE id=70",String.class)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM metadata_snapshot WHERE id=80",Long.class)).isEqualTo(6L);
        // 另一候选仍为 APPROVED，客户端持有的旧正式指针也必须单独拒绝。
        assertThatThrownBy(() -> publisher.publishSnapshot(81L, "APPROVED", 70L, 7L))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(jdbc.queryForList("SELECT id FROM metadata_snapshot WHERE status='PUBLISHED'",Long.class)).containsExactly(80L);
        publisher.revokeSnapshot(80L, "PUBLISHED", 80L, 7L, "撤回本轮候选");
        assertThat(jdbc.queryForList("SELECT id FROM metadata_snapshot WHERE status='PUBLISHED'",Long.class)).isEmpty();
        publisher.publishSnapshot(81L, "APPROVED", null, 7L);
        assertThat(jdbc.queryForList("SELECT id FROM metadata_snapshot WHERE status='PUBLISHED'",Long.class)).containsExactly(81L);
    }

    @Test
    void gateLinksUseCurrentUnresolvedStatusesAndSkipOperationsWhichNeverStarted() {
        jdbc.update("INSERT INTO metadata_quality_issue(snapshot_id,issue_domain,severity,status) VALUES(80,'SOURCE','HIGH','OPEN'),(80,'SOURCE','HIGH','REOPENED'),(80,'SOURCE','HIGH','RESOLVED'),(80,'SEMANTIC','HIGH','OPEN')");
        jdbc.update("INSERT INTO quality_check_batch VALUES(8,80,901,NULL,NULL),(9,80,901,'fenced',NULL),(10,81,901,'other','valid')");
        var actual = gate.evaluate(80L);
        assertThat(actual.getLatestCheckBatchId()).isEqualTo(8L);
        assertThat(actual.getUnresolvedSourceHigh()).isEqualTo(2);
        assertThat(actual.getUnresolvedSourceHighByStatus()).containsExactlyInAnyOrderEntriesOf(
                new java.util.LinkedHashMap<>(java.util.Map.of("OPEN",1L,"CONFIRMED",0L,"REOPENED",1L)));
    }

    @Test
    void sourceCheckWhichCommitsAfterGatePreviewPreventsTheQueuedPublish() throws Exception {
        assertThat(gate.evaluate(80L).isPublishable()).isTrue();
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch problemInserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            var check = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // 模拟来源检查已经进入最后落库阶段；发布请求此时仍只看得到上次已提交的 PASS。
                jdbc.queryForObject("SELECT id FROM datasource WHERE id=901 FOR UPDATE", Long.class);
                jdbc.update("INSERT INTO metadata_quality_issue(snapshot_id,issue_domain,severity,status) VALUES(80,'SOURCE','HIGH','OPEN')");
                problemInserted.countDown();
                try {
                    if (!commit.await(8, TimeUnit.SECONDS)) throw new AssertionError("等待检查提交超时");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }));
            assertThat(problemInserted.await(5, TimeUnit.SECONDS)).isTrue();
            var publish = pool.submit(() -> {
                try {
                    publisher.publishSnapshot(80L, "APPROVED", 70L, 7L);
                    return 200;
                } catch (BusinessException ex) {
                    return ex.getCode();
                }
            });
            // 以数据库实际等待关系或旧发布已结束为同步点，不用固定 sleep 决定谁先提交。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!publish.isDone() && jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.sessions WHERE blocker_id IS NOT NULL", Integer.class) == 0) {
                if (System.nanoTime() >= deadline) throw new AssertionError("发布未完成也未等待同源事务锁");
                Thread.sleep(10);
            }
            commit.countDown();
            check.get(5, TimeUnit.SECONDS);
            assertThat(publish.get(5, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(jdbc.queryForList("SELECT id FROM metadata_snapshot WHERE status='PUBLISHED'", Long.class)).containsExactly(70L);
            verifyNoInteractions(audit);
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
    }
}
