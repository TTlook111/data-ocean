package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.mapper.QualityCheckBatchRuleMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import com.dataocean.module.governance.service.QualityCheckBatchService;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 仅在命名 H2 内存库验证登记事务和请求竞争，不冒充 MySQL 或真实检查器验收。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quality_check_operations;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DROP TABLE IF EXISTS quality_check_batch",
        "CREATE TABLE quality_check_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY,datasource_id BIGINT,snapshot_id BIGINT,"
                + "requested_scope VARCHAR(20),requested_dimensions_json CLOB,requested_table_names_json CLOB,status VARCHAR(20),"
                + "complete INT,total_score DECIMAL(8,2),rule_count INT,executed_rule_count INT,failed_rule_count INT,"
                + "not_applicable_rule_count INT,error_summary VARCHAR(1000),started_at TIMESTAMP,finished_at TIMESTAMP,"
                + "created_by BIGINT,operation_id VARCHAR(36),request_hash VARCHAR(64),execution_owner VARCHAR(36),"
                + "UNIQUE(created_by,operation_id))"
})
class QualityCheckOperationPersistenceTest {
    @Autowired QualityCheckBatchService batches;
    @Autowired JdbcTemplate jdbc;
    @MockBean MetadataSnapshotMapper snapshots;
    @MockBean QualityCheckResultMapper results;
    @MockBean QualityCheckBatchRuleMapper rules;

    @BeforeEach
    void snapshotScope() {
        MetadataSnapshot snapshot = new MetadataSnapshot();
        snapshot.setId(4L);
        snapshot.setDatasourceId(1L);
        when(snapshots.selectById(4L)).thenReturn(snapshot);
        MetadataSnapshot another = new MetadataSnapshot();
        another.setId(5L);
        another.setDatasourceId(2L);
        when(snapshots.selectById(5L)).thenReturn(another);
    }

    @Test
    void responseLossAndRepeatedRequestRetainOneBatchAndOnlyTheFirstExecutionOwner() {
        String operation = UUID.randomUUID().toString();
        var first = start(operation);
        var replay = start(operation);
        assertThat(first.owner()).isTrue();
        assertThat(replay.owner()).isFalse();
        assertThat(replay.batchId()).isEqualTo(first.batchId());
        assertThat(batches.getOperation(4L, operation, 9L).getStatus()).isEqualTo("RUNNING");
        batches.finish(first.batchId(), "FAILED", false, null, "明确执行失败", Map.of());
        assertThat(start(operation).batch().getStatus()).isEqualTo("FAILED");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void sameIdentityCannotChangeTheFrozenScopeOrReadAnotherActorsOperation() {
        String operation = UUID.randomUUID().toString();
        start(operation);
        assertThatThrownBy(() -> batches.beginOperation(1L, 4L, "TABLE", List.of("ACCURACY"), List.of("orders"), 9L, operation))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> batches.getOperation(5L, operation, 9L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> batches.getOperation(4L, operation, 10L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void resolveFencesAnUnregisteredRequestButDoesNotPretendToStopARecordedExecution() {
        String missing = UUID.randomUUID().toString();
        assertThatThrownBy(() -> batches.getOperation(4L, missing, 9L)).isInstanceOf(BusinessException.class);
        var fence = batches.resolveOperation(4L, missing, 9L);
        assertThat(fence.getOperationOutcome()).isEqualTo("NOT_STARTED");
        assertThat(start(missing).owner()).isFalse();
        assertThat(start(missing).batch().getStatus()).isEqualTo("FAILED");
        String running = UUID.randomUUID().toString();
        var first = start(running);
        var retained = batches.resolveOperation(4L, running, 9L);
        assertThat(retained.getId()).isEqualTo(first.batchId());
        assertThat(retained.getStatus()).isEqualTo("RUNNING");
        assertThat(retained.getOperationOutcome()).isEqualTo("RECORDED");
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void concurrentSubmissionsAndResolutionHaveOneDurableWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            String operation = UUID.randomUUID().toString();
            CountDownLatch ready = new CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<QualityCheckBatchService.OperationStart>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { ready.await(); return start(operation); }));
            ready.countDown();
            int owners = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS).owner()) owners++;
            assertThat(owners).isEqualTo(1);
            assertThat(count()).isEqualTo(1);

            String race = UUID.randomUUID().toString();
            CountDownLatch raceStart = new CountDownLatch(1);
            var submit = pool.submit(() -> { raceStart.await(); return start(race); });
            var resolve = pool.submit(() -> { raceStart.await(); return batches.resolveOperation(4L, race, 9L); });
            raceStart.countDown();
            var submitted = submit.get(10, TimeUnit.SECONDS);
            var resolved = resolve.get(10, TimeUnit.SECONDS);
            assertThat(resolved.getId()).isEqualTo(submitted.batchId());
            assertThat(resolved.getOperationOutcome()).isEqualTo(submitted.owner() ? "RECORDED" : "NOT_STARTED");
            assertThat(count()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    private QualityCheckBatchService.OperationStart start(String operation) {
        return batches.beginOperation(1L, 4L, "TABLE", List.of("COMPLETENESS"), List.of("products"), 9L, operation);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM quality_check_batch", Integer.class);
    }
}
