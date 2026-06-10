package com.dataocean.module.prompt.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.prompt.enums.PromptStatus;
import com.dataocean.module.audit.entity.QueryAuditLog;
import com.dataocean.module.audit.mapper.QueryAuditLogMapper;
import com.dataocean.module.prompt.entity.PromptTemplate;
import com.dataocean.module.prompt.entity.PromptTemplateVersion;
import com.dataocean.module.prompt.entity.dto.PromptRollbackDTO;
import com.dataocean.module.prompt.entity.dto.PromptTemplateUpdateDTO;
import com.dataocean.module.prompt.entity.vo.PromptEffectivenessVO;
import com.dataocean.module.prompt.entity.vo.PromptTemplateVO;
import com.dataocean.module.prompt.entity.vo.PromptVersionVO;
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
        return toVO(getByCode(code), true);
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
        return toVO(template, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO updateTemplate(String code, PromptTemplateUpdateDTO request) {
        PromptTemplate template = getByCode(code);

        String currentStatus = template.getStatus();
        if (PromptStatus.PENDING_REVIEW.name().equals(currentStatus)) {
            throw new BusinessException(400, "待审核状态的模板不能编辑，请先完成审核");
        }

        int newVersionNo = getNextVersionNo(template.getId());

        PromptTemplateVersion newVersion = new PromptTemplateVersion();
        newVersion.setTemplateId(template.getId());
        newVersion.setVersionNo(newVersionNo);
        newVersion.setContent(request.getContent());
        newVersion.setChangeSummary(request.getChangeSummary());
        newVersion.setIsActive(false);
        newVersion.setStatus(PromptStatus.DRAFT.name());
        newVersion.setCreatedBy(UserContext.currentUserId());
        newVersion.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(newVersion);

        template.setStatus(PromptStatus.DRAFT.name());
        template.setUpdatedAt(LocalDateTime.now());
        int updated = templateMapper.updateById(template);
        if (updated == 0) {
            throw new BusinessException(409, "模板更新冲突，其他用户正在编辑，请刷新后重试");
        }

        log.info("Prompt 模板更新 code={} newVersion={} status=DRAFT", code, newVersionNo);
        return toVO(template, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO submitForReview(String code) {
        PromptTemplate template = getByCode(code);

        if (!PromptStatus.DRAFT.name().equals(template.getStatus())) {
            throw new BusinessException(400, "只有草稿状态的模板才能提交审核，当前状态：" + template.getStatus());
        }

        PromptTemplateVersion draftVersion = getLatestWorkflowVersion(template.getId(), PromptStatus.DRAFT);
        if (draftVersion == null) {
            throw new BusinessException(400, "没有可提交审核的草稿版本");
        }
        draftVersion.setStatus(PromptStatus.PENDING_REVIEW.name());
        versionMapper.updateById(draftVersion);

        template.setStatus(PromptStatus.PENDING_REVIEW.name());
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);

        log.info("Prompt 模板提交审核 code={}", code);
        return toVO(template, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO approve(String code, String changeSummary) {
        PromptTemplate template = getByCode(code);

        if (!PromptStatus.PENDING_REVIEW.name().equals(template.getStatus())) {
            throw new BusinessException(400, "只有待审核状态的模板才能审核，当前状态：" + template.getStatus());
        }

        PromptTemplateVersion pendingVersion = getLatestWorkflowVersion(template.getId(), PromptStatus.PENDING_REVIEW);
        if (pendingVersion == null) {
            throw new BusinessException(400, "没有可审核的待审核版本");
        }

        versionMapper.update(null, new LambdaUpdateWrapper<PromptTemplateVersion>()
                .eq(PromptTemplateVersion::getTemplateId, template.getId())
                .eq(PromptTemplateVersion::getIsActive, true)
                .set(PromptTemplateVersion::getIsActive, false));

        pendingVersion.setStatus(PromptStatus.APPROVED.name());
        pendingVersion.setIsActive(true);
        if (changeSummary != null && !changeSummary.isBlank()) {
            pendingVersion.setChangeSummary(changeSummary);
        }
        versionMapper.updateById(pendingVersion);

        template.setContent(pendingVersion.getContent());
        template.setCurrentVersion(pendingVersion.getVersionNo());
        template.setStatus(PromptStatus.APPROVED.name());
        template.setEnabled(true);
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);

        log.info("Prompt 模板审核通过 code={}", code);
        return toVO(template, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptTemplateVO reject(String code, String rejectReason) {
        PromptTemplate template = getByCode(code);

        if (!PromptStatus.PENDING_REVIEW.name().equals(template.getStatus())) {
            throw new BusinessException(400, "只有待审核状态的模板才能拒绝，当前状态：" + template.getStatus());
        }

        PromptTemplateVersion pendingVersion = getLatestWorkflowVersion(template.getId(), PromptStatus.PENDING_REVIEW);
        if (pendingVersion == null) {
            throw new BusinessException(400, "没有可拒绝的待审核版本");
        }
        pendingVersion.setStatus(PromptStatus.REJECTED.name());
        if (rejectReason != null && !rejectReason.isBlank()) {
            pendingVersion.setChangeSummary("拒绝原因：" + rejectReason);
        }
        versionMapper.updateById(pendingVersion);

        template.setStatus(PromptStatus.REJECTED.name());
        template.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(template);

        log.info("Prompt 模板审核拒绝 code={} reason={}", code, rejectReason);
        return toVO(template, true);
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

        if (PromptStatus.PENDING_REVIEW.name().equals(template.getStatus())) {
            throw new BusinessException(400, "待审核状态不能回滚，请先完成审核");
        }

        PromptTemplateVersion targetVersion = versionMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, template.getId())
                        .eq(PromptTemplateVersion::getVersionNo, request.getTargetVersionNo()));
        if (targetVersion == null) {
            throw new BusinessException("目标版本不存在：v" + request.getTargetVersionNo());
        }

        int newVersionNo = getNextVersionNo(template.getId());
        PromptTemplateVersion rollbackDraft = new PromptTemplateVersion();
        rollbackDraft.setTemplateId(template.getId());
        rollbackDraft.setVersionNo(newVersionNo);
        rollbackDraft.setContent(targetVersion.getContent());
        rollbackDraft.setChangeSummary("回滚到 v" + request.getTargetVersionNo());
        rollbackDraft.setIsActive(false);
        rollbackDraft.setStatus(PromptStatus.DRAFT.name());
        rollbackDraft.setCreatedBy(UserContext.currentUserId());
        rollbackDraft.setCreatedAt(LocalDateTime.now());
        versionMapper.insert(rollbackDraft);

        template.setStatus(PromptStatus.DRAFT.name());
        template.setUpdatedAt(LocalDateTime.now());
        int updated = templateMapper.updateById(template);
        if (updated == 0) {
            throw new BusinessException(409, "模板回滚冲突，其他用户正在编辑，请刷新后重试");
        }

        log.info("Prompt 模板回滚生成草稿 code={} targetVersion={} draftVersion={}", code, request.getTargetVersionNo(), newVersionNo);
        return toVO(template, true);
    }

    @Override
    public String getActiveContent(String code) {
        PromptTemplate template = templateMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplate>()
                        .eq(PromptTemplate::getTemplateCode, code)
                        .eq(PromptTemplate::getEnabled, true));
        if (template == null) {
            throw new BusinessException(404, "Prompt 模板不存在或已禁用：" + code);
        }
        return template.getContent();
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

    private int getNextVersionNo(Long templateId) {
        PromptTemplateVersion latestVersion = versionMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, templateId)
                        .orderByDesc(PromptTemplateVersion::getVersionNo)
                        .last("LIMIT 1"));
        return latestVersion == null ? 1 : latestVersion.getVersionNo() + 1;
    }

    private PromptTemplateVersion getLatestWorkflowVersion(Long templateId, PromptStatus status) {
        return versionMapper.selectOne(
                new LambdaQueryWrapper<PromptTemplateVersion>()
                        .eq(PromptTemplateVersion::getTemplateId, templateId)
                        .eq(PromptTemplateVersion::getStatus, status.name())
                        .eq(PromptTemplateVersion::getIsActive, false)
                        .orderByDesc(PromptTemplateVersion::getVersionNo)
                        .last("LIMIT 1"));
    }

    private PromptTemplateVO toVO(PromptTemplate entity) {
        return toVO(entity, true);
    }

    private PromptTemplateVO toVO(PromptTemplate entity, boolean includeWorkflowVersion) {
        PromptTemplateVO vo = new PromptTemplateVO();
        BeanUtils.copyProperties(entity, vo);
        if (includeWorkflowVersion && !PromptStatus.APPROVED.name().equals(entity.getStatus())) {
            PromptTemplateVersion workflowVersion = versionMapper.selectOne(
                    new LambdaQueryWrapper<PromptTemplateVersion>()
                            .eq(PromptTemplateVersion::getTemplateId, entity.getId())
                            .eq(PromptTemplateVersion::getIsActive, false)
                            .in(PromptTemplateVersion::getStatus,
                                    PromptStatus.DRAFT.name(),
                                    PromptStatus.PENDING_REVIEW.name(),
                                    PromptStatus.REJECTED.name())
                            .orderByDesc(PromptTemplateVersion::getVersionNo)
                            .last("LIMIT 1"));
            if (workflowVersion != null) {
                vo.setContent(workflowVersion.getContent());
                vo.setCurrentVersion(workflowVersion.getVersionNo());
                vo.setStatus(workflowVersion.getStatus());
            }
        }
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
