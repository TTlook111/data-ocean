package com.dataocean.module.governance.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.checker.QualityChecker;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import com.dataocean.module.governance.mapper.MetadataQualityIssueMapper;
import com.dataocean.module.governance.mapper.QualityCheckResultMapper;
import com.dataocean.module.governance.service.QualityCheckBatchService;
import com.dataocean.module.governance.service.QualityRuleService;
import com.dataocean.module.governance.service.QualityRuleTemplateRegistry;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import com.dataocean.module.metadata.mapper.*;
import com.dataocean.module.versioning.entity.vo.SnapshotReleaseGateVO;
import com.dataocean.module.versioning.service.SnapshotReleaseGateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用实际 MyBatis 条件 UPDATE 证明检查不会覆盖执行期间另一事务已发布的快照。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:quality_check_snapshot;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@ActiveProfiles("test")
@Sql(statements = {
        "DROP TABLE IF EXISTS metadata_snapshot",
        "CREATE TABLE metadata_snapshot (id BIGINT PRIMARY KEY,datasource_id BIGINT,snapshot_version INT,schema_hash VARCHAR(64),"
                + "status VARCHAR(30),table_count INT,column_count INT,total_rows_estimate BIGINT,quality_score DECIMAL(5,2),"
                + "sync_task_id BIGINT,reviewed_by BIGINT,reviewed_at TIMESTAMP,published_at TIMESTAMP,entity_sync_status VARCHAR(30),"
                + "entity_sync_error VARCHAR(500),entity_synced_at TIMESTAMP,expired_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)",
        "INSERT INTO metadata_snapshot(id,datasource_id,snapshot_version,status,quality_score,entity_sync_status) VALUES(4,1,2,'APPROVED',47,'SYNCED')"
})
class QualityCheckSnapshotPersistenceTest {
    @Autowired MetadataSnapshotMapper snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void publicationCommittedDuringCheckSurvivesTheRejectedOldSnapshotWrite() {
        var batches = mock(QualityCheckBatchService.class);
        QualityCheckServiceImpl service = service(batches, () -> {
            // 模拟另一个事务在检查期间完成发布；该提交不属于检查事务，不能被回滚或覆盖。
            TransactionTemplate publish = new TransactionTemplate(transactions);
            publish.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            publish.executeWithoutResult(status -> jdbc.update("UPDATE metadata_snapshot SET status='PUBLISHED',reviewed_by=7 WHERE id=4"));
        });
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                .execute(status -> service.executeQualityCheck(4L, null, null)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        var persisted = snapshots.selectById(4L);
        assertThat(persisted.getStatus()).isEqualTo("PUBLISHED");
        assertThat(persisted.getQualityScore()).isEqualByComparingTo("47");
        assertThat(persisted.getReviewedBy()).isEqualTo(7L);
        verify(batches).finish(eq(10L), eq("FAILED"), eq(false), isNull(), anyString(), any());
    }

    @Test
    void stablePublishedSnapshotKeepsPublicationAndAuxiliaryFieldsWhenItsScoreIsUpdated() {
        jdbc.update("UPDATE metadata_snapshot SET status='PUBLISHED',reviewed_by=7 WHERE id=4");
        var result = new TransactionTemplate(transactions).execute(status ->
                service(mock(QualityCheckBatchService.class), () -> {}).executeQualityCheck(4L, null, null));
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        var persisted = snapshots.selectById(4L);
        assertThat(persisted.getStatus()).isEqualTo("PUBLISHED");
        assertThat(persisted.getQualityScore()).isEqualByComparingTo("100");
        assertThat(persisted.getReviewedBy()).isEqualTo(7L);
        assertThat(persisted.getEntitySyncStatus()).isEqualTo("SYNCED");
    }

    private QualityCheckServiceImpl service(QualityCheckBatchService batches, Runnable duringCheck) {
        QualityRuleService rules = mock(QualityRuleService.class);
        MetadataQualityRule rule = new MetadataQualityRule();
        rule.setId(1L); rule.setRuleCode("QR-TEST"); rule.setTemplateCode("COMP_TABLE_COMMENT_MISSING");
        rule.setDimension("COMPLETENESS"); rule.setCheckType("SCHEMA"); rule.setCheckTarget("TABLE");
        rule.setScopeType("GLOBAL"); rule.setEnabled(1); rule.setConfigVersion(1); rule.setDeductionPoints(BigDecimal.ONE);
        when(rules.listEnabledRules(1L)).thenReturn(List.of(rule));
        DbTableMeta table = new DbTableMeta();
        table.setId(1L); table.setSnapshotId(4L); table.setTableName("products"); table.setGovernanceStatus("NORMAL");
        DbTableMetaMapper tables = mock(DbTableMetaMapper.class);
        when(tables.selectList(any())).thenReturn(List.of(table));
        QualityChecker checker = mock(QualityChecker.class);
        when(checker.getDimension()).thenReturn("COMPLETENESS");
        when(checker.check(any())).thenAnswer(invocation -> { duringCheck.run(); return List.of(); });
        when(batches.begin(any(), any(), any(), any(), any(), any())).thenReturn(10L);
        SnapshotReleaseGateService gate = mock(SnapshotReleaseGateService.class);
        SnapshotReleaseGateVO permitted = new SnapshotReleaseGateVO(); permitted.setApprovable(true);
        when(gate.evaluate(any(com.dataocean.module.metadata.entity.po.MetadataSnapshot.class))).thenReturn(permitted);
        return new QualityCheckServiceImpl(List.of(checker), rules, snapshots, tables, mock(DbColumnMetaMapper.class),
                mock(TableRelationMapper.class), mock(MetadataQualityIssueMapper.class), mock(QualityCheckResultMapper.class),
                new QualityRuleTemplateRegistry(), batches, gate);
    }
}
