package com.dataocean.module.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.entity.QueryLineageColumn;
import com.dataocean.module.audit.entity.QueryLineageTable;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.audit.mapper.QueryLineageColumnMapper;
import com.dataocean.module.audit.mapper.QueryLineageTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计数据清理定时任务
 * <p>
 * 每天凌晨 2 点执行，删除超过保留天数的审计日志和血缘数据。
 * 按批次删除避免锁表。
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditCleanupJob {

    private final QueryAuditLogMapper auditLogMapper;
    private final QueryLineageTableMapper lineageTableMapper;
    private final QueryLineageColumnMapper lineageColumnMapper;

    @Value("${dataocean.audit.retention-days:180}")
    private int retentionDays;

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpiredData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("开始清理过期审计数据，截止时间={}", cutoff);

        int auditDeleted = deleteInBatches(cutoff, "audit_log");
        int tableDeleted = deleteLineageTableInBatches(cutoff);
        int columnDeleted = deleteLineageColumnInBatches(cutoff);

        log.info("审计数据清理完成：审计日志删除 {} 条，表血缘删除 {} 条，字段血缘删除 {} 条",
                auditDeleted, tableDeleted, columnDeleted);
    }

    private int deleteInBatches(LocalDateTime cutoff, String type) {
        int totalDeleted = 0;
        int batchSize = 1000;
        while (true) {
            int deleted = auditLogMapper.delete(
                    new LambdaQueryWrapper<QueryAuditLog>()
                            .lt(QueryAuditLog::getCreatedAt, cutoff)
                            .last("LIMIT " + batchSize)
            );
            totalDeleted += deleted;
            if (deleted < batchSize) break;
        }
        return totalDeleted;
    }

    private int deleteLineageTableInBatches(LocalDateTime cutoff) {
        int totalDeleted = 0;
        int batchSize = 1000;
        while (true) {
            int deleted = lineageTableMapper.delete(
                    new LambdaQueryWrapper<QueryLineageTable>()
                            .lt(QueryLineageTable::getCreatedAt, cutoff)
                            .last("LIMIT " + batchSize)
            );
            totalDeleted += deleted;
            if (deleted < batchSize) break;
        }
        return totalDeleted;
    }

    private int deleteLineageColumnInBatches(LocalDateTime cutoff) {
        int totalDeleted = 0;
        int batchSize = 1000;
        while (true) {
            int deleted = lineageColumnMapper.delete(
                    new LambdaQueryWrapper<QueryLineageColumn>()
                            .lt(QueryLineageColumn::getCreatedAt, cutoff)
                            .last("LIMIT " + batchSize)
            );
            totalDeleted += deleted;
            if (deleted < batchSize) break;
        }
        return totalDeleted;
    }
}
