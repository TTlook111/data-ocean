package com.dataocean.module.audit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.module.audit.entity.LlmUsageLog;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.entity.vo.LlmUsageStatsVO;
import com.dataocean.module.audit.mapper.LlmUsageLogMapper;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.audit.service.LlmUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmUsageServiceImpl implements LlmUsageService {

    private final LlmUsageLogMapper usageLogMapper;
    private final QueryAuditLogMapper auditLogMapper;

    @Override
    public void recordUsage(Long queryTaskId, String provider, String model,
                            int promptTokens, int completionTokens, BigDecimal costAmount) {
        LlmUsageLog usageLog = new LlmUsageLog();
        usageLog.setQueryTaskId(queryTaskId);
        usageLog.setProvider(provider);
        usageLog.setModel(model);
        usageLog.setPromptTokens(promptTokens);
        usageLog.setCompletionTokens(completionTokens);
        usageLog.setTotalTokens(promptTokens + completionTokens);
        usageLog.setCostAmount(costAmount);
        usageLog.setCreatedAt(LocalDateTime.now());
        usageLogMapper.insert(usageLog);
    }

    @Override
    public int getDailyQueryCount(Long userId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return Math.toIntExact(auditLogMapper.selectCount(
                new LambdaQueryWrapper<QueryAuditLog>()
                        .eq(QueryAuditLog::getUserId, userId)
                        .ge(QueryAuditLog::getCreatedAt, todayStart)
        ));
    }

    @Override
    public LlmUsageStatsVO getUsageStats(int days) {
        LocalDateTime startTime = LocalDateTime.now().minusDays(days);
        List<LlmUsageLog> logs = usageLogMapper.selectList(
                new LambdaQueryWrapper<LlmUsageLog>()
                        .ge(LlmUsageLog::getCreatedAt, startTime)
        );
        LlmUsageStatsVO stats = new LlmUsageStatsVO();
        stats.setTotalCalls((long) logs.size());
        stats.setTotalTokens(logs.stream().mapToLong(LlmUsageLog::getTotalTokens).sum());
        stats.setTotalCost(logs.stream().map(LlmUsageLog::getCostAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        stats.setAvgDailyCalls(days > 0 ? (double) logs.size() / days : 0.0);
        stats.setAvgDailyCost(days > 0 ? stats.getTotalCost().divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        return stats;
    }
}
