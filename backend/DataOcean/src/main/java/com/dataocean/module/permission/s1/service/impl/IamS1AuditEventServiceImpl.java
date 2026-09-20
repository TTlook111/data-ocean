package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1AuditEvent;
import com.dataocean.module.permission.s1.mapper.IamS1AuditEventMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

/** IAM-SIMPLE-1 权限审计服务实现。 */
@Service
@RequiredArgsConstructor
public class IamS1AuditEventServiceImpl implements IamS1AuditEventService {

    /** 与 `iam_s1_audit_event` 的列长度一一对应，改动必须与迁移同步。 */
    private static final int BEFORE_AFTER_MAX = 2000;
    private static final int REASON_MAX = 500;
    private static final int EXECUTION_ID_MAX = 100;

    /** 不允许原样落入审计列的敏感词，统一就地屏蔽。 */
    private static final Pattern SENSITIVE_PATTERN =
            Pattern.compile("password|jwt|api_key|secret|token|database", Pattern.CASE_INSENSITIVE);

    private final IamS1AuditEventMapper auditEventMapper;

    @Override
    public void recordSuccess(String eventType, Long operatorId, String targetType, Long targetId,
                              String beforeSummary, String afterSummary, String reason, String executionId) {
        IamS1AuditEvent event = baseEvent(eventType, operatorId, targetType, targetId, reason, executionId);
        event.setBeforeSummary(sanitize(beforeSummary, BEFORE_AFTER_MAX));
        event.setAfterSummary(sanitize(afterSummary, BEFORE_AFTER_MAX));
        event.setSuccess(1);
        auditEventMapper.insert(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String eventType, Long operatorId, String targetType, Long targetId,
                              String reason, String failureReason, String executionId) {
        IamS1AuditEvent event = baseEvent(eventType, operatorId, targetType, targetId, reason, executionId);
        event.setSuccess(0);
        event.setFailureReason(sanitize(failureReason, REASON_MAX));
        auditEventMapper.insert(event);
    }

    private IamS1AuditEvent baseEvent(String eventType, Long operatorId, String targetType, Long targetId,
                                      String reason, String executionId) {
        if (eventType == null || eventType.isBlank() || targetType == null || targetType.isBlank()) {
            throw new BusinessException("权限审计缺少事件或目标类型");
        }
        IamS1AuditEvent event = new IamS1AuditEvent();
        event.setEventType(eventType);
        event.setOperatorId(operatorId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setReason(sanitize(reason, REASON_MAX));
        event.setExecutionId(sanitize(executionId, EXECUTION_ID_MAX));
        return event;
    }

    /**
     * 规范化写入审计列的文本：就地屏蔽敏感词，并按目标列长度截断。
     *
     * <p>这里刻意**不抛异常**，也不做“命中即拒绝”，原因有两个：</p>
     *
     * <p>① 这些字段里混着用户自由文本——申请用途（`purpose`）、审批与撤回理由、
     * 以及摘要里拼接的角色名和表名。原先命中敏感词即抛 `BusinessException`，
     * 而本方法在调用方的事务内执行：用户把用途写成“同步 database 对账数据”
     * 会让整个访问申请回滚；审批理由命中时更严重，会连带回滚已经生成的授权，
     * 而提示只有“权限审计摘要包含禁止记录的敏感字段”，与用户填写的字段完全对不上。
     * 这拦不住任何真实攻击，只是把内容策略变成了业务失败。</p>
     *
     * <p>② 原先对所有列统一按 2000 字符判断，而 `reason` / `failure_reason` 列只有
     * 500 字符：一段较长的申请用途会让插入直接失败（Data too long），同样回滚业务。</p>
     *
     * <p>屏蔽 + 按列截断同样保证敏感原值不落库，且不打断业务。</p>
     */
    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String masked = SENSITIVE_PATTERN.matcher(value.trim()).replaceAll("***");
        return masked.length() > maxLength ? masked.substring(0, maxLength) : masked;
    }
}
