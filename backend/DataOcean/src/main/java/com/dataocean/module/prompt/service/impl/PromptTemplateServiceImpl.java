package com.dataocean.module.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.prompt.entity.PromptTemplate;
import com.dataocean.module.prompt.entity.PromptTemplateVersion;
import com.dataocean.module.prompt.entity.vo.PromptEffectivenessVO;
import com.dataocean.module.prompt.entity.dto.PromptRollbackDTO;
import com.dataocean.module.prompt.entity.dto.PromptTemplateUpdateDTO;
import com.dataocean.module.prompt.entity.dto.PromptTemplateVO;
import com.dataocean.module.prompt.entity.dto.PromptVersionVO;
import com.dataocean.module.prompt.mapper.PromptTemplateMapper;
import com.dataocean.module.prompt.mapper.PromptTemplateVersionMapper;
import com.dataocean.module.prompt.service.PromptTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prompt 模板服务实现类。
 * <p>
 * 实现模板的 CRUD、版本管理、回滚和效果分析逻辑。
 * 更新操作自动创建新版本，支持按版本号回滚。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private final PromptTemplateMapper templateMapper;
    private final PromptTemplateVersionMapper versionMapper;
    private final QueryAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Page<PromptTemplateVO> listTemplates(int page, int pageSize) {
        Page<PromptTemplate> result = templateMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<PromptTemplate>().orderByAsc(PromptTemplate::getScenario));
        Page<PromptTemplateVO> voPage = new Page<>(page, pageSize, result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public PromptTemplateVO getTemplate(String code) {
        return toVO(getByCode(code));
    }

    @Override
    public PromptTemplateVO getActiveTemplate(String code) {
        PromptTemplate template = templateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateCode, code)
                        .eq(PromptTemplate::getEnabled, true));
        if (template == null) {
            throw new BusinessException(404, "Prompt 模板不存在或已禁用：" + code);
        }
        return toVO(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO updateTemplate(String code, PromptTemplateUpdateDTO request) {
        PromptTemplate template = getByCode(code);
        int newVersionNo = template.getCurrentVersion() + 1;

        versionMapper.update(null, new LambdaUpdateWrapper<PromptTemplateVersion>()
                .eq(PromptTemplateVersion::getTemplateId, template.getId())
                .eq(PromptTemplateVersion::getIsActive, true)
                .set(PromptTemplateVersion::getIsActive, false));

        PromptTemplateVersion newVersion = new PromptTemplateVersion();
        newVersion.setTemplateId(template.getId());
        newVersion.setVersionNo(newVersionNo);
        newVersion.setContent(request.getContent());
        newVersion.setChangeSummary(request.getChangeSummary());
        newVersion.setIsActive(true);
        newVersion.setCreatedBy(UserContext.currentUserId());
        newVersion.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(newVersion);

        template.setContent(request.getContent());
        template.setCurrentVersion(newVersionNo);
        template.setUpdatedAt(LocalDateTime.now());
        int updated = templateMapper.updateById(template);
        if (updated == 0) {
            throw new BusinessException(409, "模板更新冲突，其他用户正在编辑，请刷新后重试");
        }

        log.info("Prompt 模板更新 code={} newVersion={}", code, newVersionNo);
        return toVO(template);
    }

    @Override
    public List<PromptVersionVO> getVersionHistory(String code) {
        PromptTemplate template = getByCode(code);
        List<PromptTemplateVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, template.getId())
                        .orderByDesc(PromptTemplateVersion::getVersionNo));
        return versions.stream().map(this::toVersionVO).collect(Collectors.toList());
    }

    /**
     * 按 Prompt 模板版本聚合查询效果数据。
     * <p>
     * 分批加载审计日志（每批 2000 条），避免大时间范围下一次性加载过多数据导致内存压力。
     * </p>
     */
    @Override
    public List<PromptEffectivenessVO> getEffectiveness(int days) {
        int safeDays = Math.max(1, Math.min(days, 365));
        LocalDateTime startTime = LocalDateTime.now().minusDays(safeDays);

        // 分批加载审计日志，每批 2000 条
        final int batchSize = 2000;
        Map<PromptVersionKey, EffectAccumulator> grouped = new HashMap<>();
        int currentPage = 1;
        boolean hasMore = true;

        while (hasMore) {
            Page<QueryAuditLog> page = auditLogMapper.selectPage(
                    new Page<>(currentPage, batchSize, false),
                    new LambdaQueryWrapper<QueryAuditLog>()
                            .ge(QueryAuditLog::getCreatedAt, startTime)
                            .isNotNull(QueryAuditLog::getPromptVersions)
                            .orderByAsc(QueryAuditLog::getId));
            List<QueryAuditLog> records = page.getRecords();
            for (QueryAuditLog auditLog : records) {
                for (PromptVersionKey key : extractPromptVersions(auditLog.getPromptVersions())) {
                    grouped.computeIfAbsent(key, unused -> new EffectAccumulator()).add(auditLog);
                }
            }
            hasMore = records.size() == batchSize;
            currentPage++;
        }

        return grouped.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<PromptVersionKey, EffectAccumulator> entry) -> entry.getKey().templateCode())
                        .thenComparing(entry -> entry.getKey().versionNo()))
                .map(entry -> entry.getValue().toVO(entry.getKey()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO rollback(String code, PromptRollbackDTO request) {
        PromptTemplate template = getByCode(code);

        PromptTemplateVersion targetVersion = versionMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, template.getId())
                        .eq(PromptTemplateVersion::getVersionNo, request.getTargetVersionNo()));
        if (targetVersion == null) {
            throw new BusinessException("目标版本不存在：v" + request.getTargetVersionNo());
        }

        versionMapper.update(null, new LambdaUpdateWrapper<PromptTemplateVersion>()
                .eq(PromptTemplateVersion::getTemplateId, template.getId())
                .eq(PromptTemplateVersion::getIsActive, true)
                .set(PromptTemplateVersion::getIsActive, false));

        targetVersion.setIsActive(true);
        versionMapper.updateById(targetVersion);

        template.setContent(targetVersion.getContent());
        template.setCurrentVersion(request.getTargetVersionNo());
        template.setUpdatedAt(LocalDateTime.now());
        int updated = templateMapper.updateById(template);
        if (updated == 0) {
            throw new BusinessException(409, "模板回滚冲突，其他用户正在编辑，请刷新后重试");
        }

        log.info("Prompt 模板回滚 code={} targetVersion={}", code, request.getTargetVersionNo());
        return toVO(template);
    }

    @Override
    public String getActiveContent(String code) {
        return getActiveTemplate(code).getContent();
    }

    private PromptTemplate getByCode(String code) {
        PromptTemplate template = templateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateCode, code));
        if (template == null) {
            throw new BusinessException(404, "Prompt 模板不存在：" + code);
        }
        return template;
    }

    private Set<PromptVersionKey> extractPromptVersions(String promptVersionsJson) {
        Set<PromptVersionKey> versions = new LinkedHashSet<>();
        if (promptVersionsJson == null || promptVersionsJson.isBlank()) {
            return versions;
        }
        try {
            List<Map<String, Object>> rawVersions = objectMapper.readValue(promptVersionsJson, new TypeReference<>() {});
            for (Map<String, Object> raw : rawVersions) {
                String templateCode = String.valueOf(raw.getOrDefault("templateCode", ""));
                if (templateCode.isBlank()) {
                    continue;
                }
                versions.add(new PromptVersionKey(templateCode, parseVersionNo(raw.get("versionNo"))));
            }
        } catch (Exception e) {
            log.warn("解析 Prompt 版本审计数据失败: {}", promptVersionsJson, e);
        }
        return versions;
    }

    private int parseVersionNo(Object versionNo) {
        if (versionNo instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(versionNo));
        } catch (Exception e) {
            return 0;
        }
    }

    private PromptTemplateVO toVO(PromptTemplate entity) {
        PromptTemplateVO vo = new PromptTemplateVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private PromptVersionVO toVersionVO(PromptTemplateVersion entity) {
        PromptVersionVO vo = new PromptVersionVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    private record PromptVersionKey(String templateCode, Integer versionNo) {
    }

    private static class EffectAccumulator {
        private long totalQueries;
        private long successCount;
        private long totalExecutionTimeMs;
        private long timedQueries;
        private long feedbackCount;
        private long positiveFeedbackCount;

        void add(QueryAuditLog auditLog) {
            totalQueries++;
            if (Boolean.TRUE.equals(auditLog.getIsSuccess())) {
                successCount++;
            }
            if (auditLog.getExecutionTimeMs() != null) {
                totalExecutionTimeMs += auditLog.getExecutionTimeMs();
                timedQueries++;
            }
            if (auditLog.getUserFeedback() != null && !auditLog.getUserFeedback().isBlank()) {
                feedbackCount++;
                if ("LIKE".equalsIgnoreCase(auditLog.getUserFeedback())) {
                    positiveFeedbackCount++;
                }
            }
        }

        PromptEffectivenessVO toVO(PromptVersionKey key) {
            PromptEffectivenessVO vo = new PromptEffectivenessVO();
            vo.setTemplateCode(key.templateCode());
            vo.setVersionNo(key.versionNo());
            vo.setTotalQueries(totalQueries);
            vo.setSuccessCount(successCount);
            vo.setSuccessRate(percent(successCount, totalQueries));
            vo.setAvgExecutionTimeMs(timedQueries == 0 ? 0.0 : (double) totalExecutionTimeMs / timedQueries);
            vo.setFeedbackCount(feedbackCount);
            vo.setPositiveFeedbackCount(positiveFeedbackCount);
            vo.setPositiveFeedbackRate(percent(positiveFeedbackCount, feedbackCount));
            return vo;
        }

        private double percent(long numerator, long denominator) {
            if (denominator == 0) {
                return 0.0;
            }
            return Math.round((double) numerator / denominator * 10000.0) / 100.0;
        }
    }
}
