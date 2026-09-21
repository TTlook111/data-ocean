package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1AccessApproval;
import com.dataocean.module.permission.s1.entity.IamS1AccessRequest;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1UserIdentity;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessRequestSubmitDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessReviewDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantColumnDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantSaveDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AccessApprovalVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AccessRequestVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.mapper.IamS1AccessApprovalMapper;
import com.dataocean.module.permission.s1.mapper.IamS1AccessRequestMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.service.IamS1AccessRequestService;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataGrantService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1Labels;
import com.dataocean.module.permission.s1.support.IamS1MetadataValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IAM-SIMPLE-1 访问申请与审批实现。
 * <p>
 * 申请只保存资源标识与申请范围，不保存业务原值；审批通过生成的新授权事实使用新的前向表，
 * 不读取也不复用旧审批与旧数据授权策略。
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IamS1AccessRequestServiceImpl implements IamS1AccessRequestService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MINE_LIMIT = 100;
    /** 审批生成的临时授权最长天数：审批只能给出有期限的临时授权，不允许永久。 */
    private static final int MAX_APPROVAL_DAYS = 30;
    /**
     * 审批时唯一可以容忍的原因码：明确表示“没有单份授权完整覆盖本次批准字段”，
     * 这正是审批要补足的缺口。
     * <p>
     * 其余一切（命中禁止、事实读取失败、revision 不可用、部门路径损坏、快照/表/字段不在已发布快照等）
     * 都必须 fail-closed：统一 Resolver 会把内部异常转成 `FACT_READ_FAILED` 等拒绝快照而不是抛出，
     * 只拒绝三个明确的 DENY 码会让未知失败被当成“没有禁止”而放行审批。
     * </p>
     */
    private static final Set<String> APPROVAL_TOLERATED_REASONS = Set.of("NO_ALLOW_COVERING_FIELDS");

    private final IamS1AccessRequestMapper accessRequestMapper;
    private final IamS1AccessApprovalMapper accessApprovalMapper;
    private final IamS1DataGrantMapper dataGrantMapper;
    private final IamS1FieldProtectionMapper fieldProtectionMapper;
    private final IamS1UserIdentityMapper userIdentityMapper;
    private final IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    private final IamS1SubjectQueryMapper subjectQueryMapper;
    private final IamS1MetadataValidationService metadataValidationService;
    private final IamS1CapabilityService capabilityService;
    private final IamS1DataGrantService dataGrantService;
    private final IamS1PermissionRevisionService revisionService;
    private final IamS1AuditEventService auditEventService;
    private final IamS1AdminGuard adminGuard;
    /** 禁止规则判定复用统一 Resolver，保证与真实查询同一套主体匹配、期限和字段判定。 */
    private final IamS1DataAuthorizationResolver dataAuthorizationResolver;

    @Override
    @Transactional
    public Long submit(Long requesterId, IamS1AccessRequestSubmitDTO request) {
        Long requestId = null;
        try {
            adminGuard.requireGlobalFunction(requesterId, "query:use");
            if (request == null) {
                throw new BusinessException("申请内容不能为空");
            }
            if (request.getPurpose() == null || request.getPurpose().isBlank()) {
                throw new BusinessException("请填写申请用途");
            }
            if (!IamS1Constants.ROW_MATCH_ALL.equalsIgnoreCase(
                    request.getRowScope() == null ? IamS1Constants.ROW_MATCH_ALL : request.getRowScope())) {
                throw new BusinessException("第一期申请只支持全部记录，不接受记录条件或手写 SQL");
            }
            ensureEnabledDatasource(request.getDatasourceId());
            List<String> columns = normalizeColumns(request.getColumns());
            metadataValidationService.requireColumns(request.getDatasourceId(),
                    request.getMetadataSnapshotId(), request.getTableName(), columns);
            LocalDateTime now = LocalDateTime.now();
            if (request.getRequestedValidUntil() != null && !request.getRequestedValidUntil().isAfter(now)) {
                throw new BusinessException("申请结束时间必须晚于当前时间");
            }

            IamS1AccessRequest entity = new IamS1AccessRequest();
            entity.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
            entity.setRequesterId(requesterId);
            entity.setDatasourceId(request.getDatasourceId());
            entity.setMetadataSnapshotId(request.getMetadataSnapshotId());
            entity.setTableName(request.getTableName().trim());
            entity.setRequestedColumnsJson(writeColumns(columns));
            entity.setRowScope(IamS1Constants.ROW_MATCH_ALL);
            entity.setRequestedValidUntil(request.getRequestedValidUntil());
            entity.setPurpose(request.getPurpose().trim());
            entity.setStatus("PENDING");
            entity.setRevisionNo(0L);
            entity.setCreatedBy(requesterId);
            entity.setUpdatedBy(requesterId);
            accessRequestMapper.insert(entity);
            requestId = entity.getId();
            if (requestId == null) {
                throw new BusinessException("访问申请写入失败");
            }
            Long revisionNo = revisionService.record("ACCESS_REQUEST", requestId, "SUBMIT", requesterId,
                    request.getPurpose());
            entity.setRevisionNo(revisionNo);
            accessRequestMapper.updateById(entity);
            auditEventService.recordSuccess("ACCESS_REQUEST_SUBMITTED", requesterId, "ACCESS_REQUEST", requestId,
                    null, "datasourceId=" + request.getDatasourceId() + ";table=" + entity.getTableName()
                            + ";revision=" + revisionNo, request.getPurpose(), UUID.randomUUID().toString());
            return requestId;
        } catch (RuntimeException exception) {
            recordFailureSafely("ACCESS_REQUEST_SUBMIT_FAILED", requesterId, requestId,
                    request == null ? null : request.getPurpose(), exception);
            throw exception;
        }
    }

    @Override
    @Transactional
    public void withdraw(Long requesterId, Long requestId, String reason) {
        try {
            adminGuard.requireGlobalFunction(requesterId, "query:use");
            IamS1AccessRequest request = requireRequest(requestId);
            if (!request.getRequesterId().equals(requesterId)) {
                throw new BusinessException("只能撤回本人的申请");
            }
            if (!"PENDING".equals(request.getStatus())) {
                throw new BusinessException("该申请已处理，无法撤回");
            }
            Long revisionNo = revisionService.record("ACCESS_REQUEST", requestId, "WITHDRAW", requesterId, reason);
            int updated = accessRequestMapper.updateStatusIfPending(requestId, "WITHDRAWN", revisionNo, requesterId);
            if (updated != 1) {
                throw new BusinessException("该申请已被处理，请刷新后重试");
            }
            auditEventService.recordSuccess("ACCESS_REQUEST_WITHDRAWN", requesterId, "ACCESS_REQUEST", requestId,
                    null, "revision=" + revisionNo, reason, UUID.randomUUID().toString());
        } catch (RuntimeException exception) {
            recordFailureSafely("ACCESS_REQUEST_WITHDRAW_FAILED", requesterId, requestId, reason, exception);
            throw exception;
        }
    }

    @Override
    public List<IamS1AccessRequestVO> listMine(Long requesterId) {
        adminGuard.requireGlobalFunction(requesterId, "query:use");
        List<IamS1AccessRequest> requests = accessRequestMapper.selectByRequester(
                IamS1Constants.PROTOCOL_VERSION, requesterId, MINE_LIMIT);
        return toVOs(requests);
    }

    @Override
    public Page<IamS1AccessRequestVO> listQueue(Long reviewerId, String statusGroup, int page, int size) {
        // 队列可见性必须按“功能与负责源在同一角色绑定上同时成立”判定。
        // 原先用 requireGlobalFunction（任意绑定）叠加 responsibleDatasources（任意绑定），
        // 两条判定可由两个不同角色分别满足：持有“查看访问申请”但无负责源的角色，
        // 会借另一个角色的负责源看到该源上的全部申请。
        List<IamS1DatasourceRefVO> datasources =
                capabilityService.responsibleDatasourcesWithFunction(reviewerId, "security:approval:view");
        if (datasources.isEmpty()) {
            throw new BusinessException(
                    "没有可查看的审批队列：需要“查看访问申请”功能与负责源在同一个角色绑定上同时成立");
        }
        List<Long> datasourceIds = datasources.stream().map(IamS1DatasourceRefVO::id).toList();
        List<String> statuses = queueStatuses(statusGroup);
        // 单条跨源查询 + 数据库分页。原实现是“每个负责源各取固定 100 条再合并”：
        // 待审批记录会被同源的已处理记录挤出，且超出部分没有任何入口能看到或处理。
        LambdaQueryWrapper<IamS1AccessRequest> wrapper = new LambdaQueryWrapper<IamS1AccessRequest>()
                .eq(IamS1AccessRequest::getProtocolVersion, IamS1Constants.PROTOCOL_VERSION)
                .in(IamS1AccessRequest::getDatasourceId, datasourceIds)
                .in(statuses != null, IamS1AccessRequest::getStatus, statuses)
                .orderByDesc(IamS1AccessRequest::getId);
        Page<IamS1AccessRequest> found =
                accessRequestMapper.selectPage(new Page<>(page, size), wrapper);
        Page<IamS1AccessRequestVO> result =
                new Page<>(found.getCurrent(), found.getSize(), found.getTotal());
        result.setRecords(toVOs(found.getRecords()));
        return result;
    }

    /** 队列分组 → 具体状态集合；null 表示不按状态过滤。 */
    private List<String> queueStatuses(String statusGroup) {
        if (statusGroup == null || statusGroup.isBlank()) {
            return null;
        }
        return switch (statusGroup.trim().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> List.of("PENDING");
            case "HANDLED" -> List.of("APPROVED", "REJECTED", "WITHDRAWN");
            default -> throw new BusinessException("审批队列状态只支持 PENDING、HANDLED 或留空");
        };
    }

    @Override
    @Transactional
    public Long review(Long reviewerId, Long requestId, IamS1AccessReviewDTO review) {
        try {
            IamS1AccessRequest request = accessRequestMapper.selectForUpdate(requestId);
            if (request == null || !IamS1Constants.PROTOCOL_VERSION.equals(request.getProtocolVersion())) {
                throw new BusinessException("IAM-SIMPLE-1 访问申请不存在");
            }
            if (!"PENDING".equals(request.getStatus())) {
                throw new BusinessException("该申请已处理，无法重复审批");
            }
            adminGuard.requireDatasourceFunction(reviewerId, "security:approval:review", request.getDatasourceId());
            if (reviewerId.equals(request.getRequesterId())) {
                throw new BusinessException("不能审批本人的申请");
            }
            String decision = review == null || review.getDecision() == null
                    ? null : review.getDecision().trim().toUpperCase();
            if (!Set.of("APPROVE", "REJECT").contains(decision)) {
                throw new BusinessException("审批结论只支持同意或拒绝");
            }
            List<String> requestedColumns = readColumns(request.getRequestedColumnsJson());
            String reason = review.getReason();
            Long grantId = null;
            IamS1AccessApproval approval = new IamS1AccessApproval();
            approval.setRequestId(requestId);
            approval.setReviewerId(reviewerId);
            if ("APPROVE".equals(decision)) {
                approval.setDecision("APPROVED");
                List<String> approvedColumns = normalizeApprovedColumns(request, requestedColumns, review);
                LocalDateTime approvedValidUntil = resolveApprovedValidUntil(request, review);
                ensureNoDenyRule(request, approvedColumns);
                ensureNoHiddenColumn(request, approvedColumns);
                Map<String, IamS1ColumnFact> facts = metadataValidationService.requireColumns(
                        request.getDatasourceId(), request.getMetadataSnapshotId(), request.getTableName(),
                        approvedColumns);
                IamS1DataGrantSaveDTO grantRequest = buildApprovalGrant(request, approvedColumns, facts,
                        approvedValidUntil, reason);
                grantId = dataGrantService.createApprovalGrant(reviewerId, requestId, grantRequest);
                approval.setApprovedColumnsJson(writeColumns(approvedColumns));
                approval.setApprovedValidFrom(grantRequest.getValidFrom());
                approval.setApprovedValidUntil(approvedValidUntil);
                approval.setGeneratedGrantId(grantId);
            } else {
                approval.setDecision("REJECTED");
            }
            approval.setReason(reason);
            try {
                accessApprovalMapper.insert(approval);
            } catch (DuplicateKeyException exception) {
                throw new BusinessException("该申请已被审批，请刷新后重试");
            }
            Long revisionNo = revisionService.record("ACCESS_REQUEST", requestId,
                    "APPROVE".equals(decision) ? "APPROVE" : "REJECT", reviewerId, reason);
            int updated = accessRequestMapper.updateStatusIfPending(requestId,
                    "APPROVE".equals(decision) ? "APPROVED" : "REJECTED", revisionNo, reviewerId);
            if (updated != 1) {
                throw new BusinessException("该申请已被处理，请刷新后重试");
            }
            auditEventService.recordSuccess("ACCESS_REQUEST_REVIEWED", reviewerId, "ACCESS_REQUEST", requestId,
                    null, "decision=" + approval.getDecision()
                            + (grantId == null ? "" : ";grantId=" + grantId) + ";revision=" + revisionNo,
                    reason, UUID.randomUUID().toString());
            return grantId;
        } catch (RuntimeException exception) {
            recordFailureSafely("ACCESS_REQUEST_REVIEW_FAILED", reviewerId, requestId,
                    review == null ? null : review.getReason(), exception);
            throw exception;
        }
    }

    private void ensureEnabledDatasource(Long datasourceId) {
        IamS1DatasourceFact fact = datasourceId == null ? null : datasourceIdentityMapper.selectIdentity(datasourceId);
        if (fact == null || !Integer.valueOf(IamS1Constants.ENABLED).equals(fact.getStatus())) {
            throw new BusinessException("目标数据源不存在或未启用");
        }
    }

    private List<String> normalizeColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new BusinessException("请至少选择一个字段");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                throw new BusinessException("字段名不能为空");
            }
            distinct.add(column.trim());
        }
        return List.copyOf(distinct);
    }

    private List<String> normalizeApprovedColumns(IamS1AccessRequest request, List<String> requestedColumns,
                                                  IamS1AccessReviewDTO review) {
        List<String> approved = review.getApprovedColumns() == null || review.getApprovedColumns().isEmpty()
                ? requestedColumns : normalizeColumns(review.getApprovedColumns());
        Set<String> requested = new LinkedHashSet<>(requestedColumns);
        for (String column : approved) {
            if (!requested.contains(column)) {
                throw new BusinessException("批准字段必须是申请字段的子集：" + column);
            }
        }
        return approved;
    }

    /**
     * 审批只能生成“有期限的个人临时授权”：批准到期时间必填，且不得超过申请时长与系统上限中的较小值。
     * <p>
     * 允许为空会直接生成永久授权（{@code valid_until = NULL}），因此这里对直接 API 调用同样强制校验，
     * 不依赖前端默认值。
     * </p>
     */
    private LocalDateTime resolveApprovedValidUntil(IamS1AccessRequest request, IamS1AccessReviewDTO review) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime approved = review == null ? null : review.getApprovedValidUntil();
        if (approved == null) {
            throw new BusinessException("审批通过必须指定批准到期时间：审批只会生成有期限的临时授权");
        }
        if (!approved.isAfter(now)) {
            throw new BusinessException("批准到期时间必须晚于当前时间");
        }
        LocalDateTime systemLimit = now.plusDays(MAX_APPROVAL_DAYS);
        if (approved.isAfter(systemLimit)) {
            throw new BusinessException("批准到期时间不能超过系统上限 " + MAX_APPROVAL_DAYS + " 天");
        }
        LocalDateTime requestedLimit = request.getRequestedValidUntil();
        if (requestedLimit != null && approved.isAfter(requestedLimit)) {
            throw new BusinessException("批准时间不能超过申请时长");
        }
        return approved;
    }

    /**
     * 禁止规则判定复用统一 Resolver（{@link IamS1DataAuthorizationResolver}），
     * 由它完成主体匹配（用户 / 角色 / 部门继承）、有效期与字段级判定，而不是在这里实现第二套简化算法——
     * 否则其他用户、其他角色的 DENY，或已过期的 DENY 都会错误阻断本次审批。
     * <p>
     * 只有统一 Resolver 明确给出“命中禁止”的原因码时才拒绝开通；
     * “没有单份授权完整覆盖字段”等原因码正是审批要补足的缺口，不阻断。
     * </p>
     */
    private void ensureNoDenyRule(IamS1AccessRequest request, List<String> approvedColumns) {
        Map<String, String> levelsByName = protectionLevelsByName(request.getDatasourceId(),
                request.getMetadataSnapshotId());
        com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot snapshot;
        try {
            var table = new com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO(
                    request.getTableName(), new LinkedHashSet<>(approvedColumns));
            Map<String, Set<com.dataocean.module.permission.s1.enums.IamS1ColumnUsage>> usages = new LinkedHashMap<>();
            for (String column : approvedColumns) {
                // 脱敏字段只能直接投影，否则统一 Resolver 会以 MASKED_FIELD_USAGE_FORBIDDEN 拒绝，
                // 而该原因码不在可容忍白名单里，会误伤“批准脱敏字段”这种合法审批。
                usages.put(column, com.dataocean.module.permission.s1.support.IamS1UsageDefaults
                        .forProtectionLevel(levelsByName.getOrDefault(column,
                                com.dataocean.module.permission.s1.IamS1Constants.PROTECTION_NORMAL)));
            }
            table.setColumnUsages(usages);
            var preview = new com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO();
            preview.setProtocolVersion(com.dataocean.module.permission.s1.IamS1Constants.PROTOCOL_VERSION);
            preview.setUserId(request.getRequesterId());
            preview.setDatasourceId(request.getDatasourceId());
            preview.setActiveMetadataSnapshotId(request.getMetadataSnapshotId());
            preview.setCalculatedAt(LocalDateTime.now());
            preview.setTables(List.of(table));
            snapshot = dataAuthorizationResolver.resolve(preview);
        } catch (RuntimeException exception) {
            // 事实读取失败时 fail-closed：不因为无法确认而放开审批。
            throw new BusinessException("无法确认申请人当前权限事实，拒绝审批开通");
        }
        if (snapshot == null) {
            throw new BusinessException("无法确认申请人当前权限事实，拒绝审批开通");
        }
        if (snapshot.isAllowed()) {
            return;
        }
        if (APPROVAL_TOLERATED_REASONS.contains(snapshot.getReasonCode())) {
            // 明确表示“没有单份授权完整覆盖本次批准字段”，正是审批要补足的缺口，不阻断。
            return;
        }
        // 命中禁止、事实读取失败、revision 不可用、部门路径损坏等一律拒绝：
        // resolve() 会把内部异常转成拒绝快照而不是抛出，这里不能把它当成“没有禁止”。
        throw new BusinessException("无法确认申请人当前权限事实或命中禁止规则，拒绝审批开通："
                + denyReasonName(snapshot.getReasonCode()));
    }

    /** 按字段名取保护等级（更严格的优先），用于生成 usage。 */
    private Map<String, String> protectionLevelsByName(Long datasourceId, Long snapshotId) {
        Map<String, String> levels = new LinkedHashMap<>();
        List<IamS1FieldProtection> protections = fieldProtectionMapper.selectActiveBySnapshot(
                com.dataocean.module.permission.s1.IamS1Constants.PROTOCOL_VERSION, datasourceId, snapshotId);
        if (protections == null) {
            return levels;
        }
        for (IamS1FieldProtection protection : protections) {
            if (protection.getColumnName() != null) {
                levels.merge(protection.getColumnName(), protection.getProtectionLevel(),
                        IamS1Constants::stricterProtection);
            }
        }
        return levels;
    }

    private String denyReasonName(String reasonCode) {
        if (reasonCode == null) {
            return "未知原因";
        }
        return switch (reasonCode) {
            case "DATASOURCE_DENY" -> "整个数据源被禁止查询";
            case "TABLE_DENY" -> "该表被禁止查询";
            case "FIELD_DENY" -> "批准字段命中字段级禁止";
            case "FACT_READ_FAILED" -> "权限事实读取失败";
            case "REVISION_UNAVAILABLE" -> "权限修订不可用";
            case "DEPARTMENT_PATH_INVALID" -> "申请人部门路径无效";
            case "USER_NOT_ENABLED" -> "申请人账号不存在或已禁用";
            case "DATASOURCE_NOT_ENABLED" -> "数据源未启用";
            case "SNAPSHOT_NOT_PUBLISHED" -> "元数据快照不存在或未发布";
            case "TABLE_NOT_IN_SNAPSHOT" -> "表不在当前已发布快照中";
            case "COLUMN_NOT_IN_SNAPSHOT" -> "字段不在当前已发布快照中";
            case "MISSING_REQUIRED_PARAMETER" -> "缺少必填参数";
            case "UNKNOWN_PROTOCOL" -> "协议不是 IAM-SIMPLE-1";
            case "FIELD_HIDDEN" -> "字段已被隐藏";
            case "MASKED_FIELD_USAGE_FORBIDDEN" -> "脱敏字段只能直接投影";
            default -> reasonCode;
        };
    }

    private void ensureNoHiddenColumn(IamS1AccessRequest request, List<String> approvedColumns) {
        List<IamS1FieldProtection> protections = fieldProtectionMapper.selectActiveBySnapshot(
                IamS1Constants.PROTOCOL_VERSION, request.getDatasourceId(), request.getMetadataSnapshotId());
        if (protections == null) {
            throw new BusinessException("字段保护事实读取失败，拒绝审批开通");
        }
        Set<String> hidden = new LinkedHashSet<>();
        for (IamS1FieldProtection protection : protections) {
            if (IamS1Constants.PROTECTION_HIDDEN.equals(protection.getProtectionLevel())
                    && protection.getColumnName() != null) {
                hidden.add(protection.getColumnName());
            }
        }
        for (String column : approvedColumns) {
            if (hidden.contains(column)) {
                throw new BusinessException("隐藏字段不能审批开通：" + column);
            }
        }
    }

    private IamS1DataGrantSaveDTO buildApprovalGrant(IamS1AccessRequest request, List<String> approvedColumns,
                                                     Map<String, IamS1ColumnFact> facts,
                                                     LocalDateTime approvedValidUntil, String reason) {
        IamS1DataGrantSaveDTO dto = new IamS1DataGrantSaveDTO();
        dto.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        dto.setSubjectType(IamS1Constants.SUBJECT_USER);
        dto.setSubjectId(request.getRequesterId());
        dto.setDatasourceId(request.getDatasourceId());
        dto.setResourceScope(IamS1Constants.RESOURCE_SCOPE_TABLE);
        dto.setMetadataSnapshotId(request.getMetadataSnapshotId());
        dto.setTableName(request.getTableName());
        dto.setEffect(IamS1Constants.EFFECT_ALLOW);
        dto.setStatus(IamS1Constants.DATA_GRANT_STATUS_ACTIVE);
        dto.setValidFrom(LocalDateTime.now());
        dto.setValidUntil(approvedValidUntil);
        dto.setReason(reason);
        List<IamS1DataGrantColumnDTO> columns = new ArrayList<>();
        for (String columnName : approvedColumns) {
            IamS1ColumnFact fact = facts.get(columnName);
            if (fact == null) {
                throw new BusinessException("批准字段与当前已发布快照不匹配：" + columnName);
            }
            IamS1DataGrantColumnDTO column = new IamS1DataGrantColumnDTO();
            column.setColumnMetaId(fact.getId());
            column.setColumnName(fact.getColumnName());
            columns.add(column);
        }
        dto.setColumns(columns);
        return dto;
    }

    private List<IamS1AccessRequestVO> toVOs(List<IamS1AccessRequest> requests) {
        List<IamS1AccessRequestVO> result = new ArrayList<>();
        if (requests == null) {
            return result;
        }
        Map<Long, String> userNames = new LinkedHashMap<>();
        Map<Long, String> datasourceNames = new LinkedHashMap<>();
        for (IamS1AccessRequest request : requests) {
            String requesterName = userNames.computeIfAbsent(request.getRequesterId(), this::userName);
            String datasourceName = datasourceNames.computeIfAbsent(request.getDatasourceId(), this::datasourceName);
            IamS1AccessApproval approval = accessApprovalMapper.selectByRequestId(request.getId());
            result.add(new IamS1AccessRequestVO(request.getId(), request.getRequesterId(), requesterName,
                    request.getDatasourceId(), datasourceName, request.getMetadataSnapshotId(),
                    request.getTableName(), readColumns(request.getRequestedColumnsJson()),
                    request.getRowScope(), IamS1Labels.rowScopeName(request.getRowScope()),
                    request.getRequestedValidUntil(), request.getPurpose(), request.getStatus(),
                    IamS1Labels.requestStatusName(request.getStatus()), request.getCreatedAt(),
                    approval == null ? null : new IamS1AccessApprovalVO(approval.getDecision(),
                            IamS1Labels.decisionName(approval.getDecision()), approval.getReviewerId(),
                            userName(approval.getReviewerId()), readColumns(approval.getApprovedColumnsJson()),
                            approval.getApprovedValidFrom(), approval.getApprovedValidUntil(),
                            approval.getGeneratedGrantId(), approval.getReason(), approval.getCreatedAt())));
        }
        return result;
    }

    private IamS1AccessRequest requireRequest(Long requestId) {
        if (requestId == null) {
            throw new BusinessException("访问申请 ID 不能为空");
        }
        IamS1AccessRequest request = accessRequestMapper.selectById(requestId);
        if (request == null || !IamS1Constants.PROTOCOL_VERSION.equals(request.getProtocolVersion())) {
            throw new BusinessException("IAM-SIMPLE-1 访问申请不存在");
        }
        return request;
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        IamS1UserIdentity identity = userIdentityMapper.selectIdentity(userId);
        if (identity == null) {
            return null;
        }
        Map<String, Object> brief = subjectQueryMapper.selectUserBrief(userId);
        if (brief == null) {
            return null;
        }
        Object realName = brief.get("real_name");
        Object username = brief.get("username");
        return realName == null || String.valueOf(realName).isBlank()
                ? String.valueOf(username) : String.valueOf(realName);
    }

    private String datasourceName(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        IamS1DatasourceFact fact = datasourceIdentityMapper.selectIdentity(datasourceId);
        return fact == null ? null : fact.getName();
    }

    private String writeColumns(List<String> columns) {
        try {
            return OBJECT_MAPPER.writeValueAsString(columns == null ? List.of() : columns);
        } catch (Exception exception) {
            throw new BusinessException("字段列表序列化失败");
        }
    }

    private List<String> readColumns(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json,
                    OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception exception) {
            throw new BusinessException("申请字段列表无法解析");
        }
    }

    private void recordFailureSafely(String eventType, Long operatorId, Long targetId,
                                     String reason, RuntimeException exception) {
        try {
            auditEventService.recordFailure(eventType, operatorId, "ACCESS_REQUEST", targetId,
                    reason, exception.getMessage(), UUID.randomUUID().toString());
        } catch (RuntimeException auditException) {
            log.error("S1 访问申请失败审计写入失败 targetId={}", targetId, auditException);
        }
    }
}
