package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1AuditEvent;
import com.dataocean.module.permission.s1.mapper.IamS1AuditEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * B4 复审：审计写入不得因为文本内容或长度而中断业务。
 *
 * <p>审计字段里混着用户自由文本（申请用途、审批与撤回理由，以及摘要里拼接的角色名和表名），
 * 所以“命中敏感词即抛异常”会把内容策略变成业务失败。</p>
 */
class IamS1AuditEventServiceImplTest {

    private final IamS1AuditEventMapper auditEventMapper = mock(IamS1AuditEventMapper.class);
    private final IamS1AuditEventServiceImpl service = new IamS1AuditEventServiceImpl(auditEventMapper);

    @Test
    void userFacingTextContainingSensitiveWordIsMaskedInsteadOfRejected() {
        // 回归：用户把申请用途写成“同步 database 对账数据”时，旧实现抛 BusinessException，
        // 而该调用在 submit 的事务内，会让整个访问申请回滚，提示还只说“审计摘要包含敏感字段”。
        Throwable thrown = catchThrowable(() -> service.recordSuccess(
                "ACCESS_REQUEST_SUBMITTED", 2L, "ACCESS_REQUEST", 9L,
                null, "datasourceId=5", "同步 database 对账数据", "exec-1"));

        assertThat(thrown).isNull();
        IamS1AuditEvent saved = captureInserted();
        assertThat(saved.getReason()).isEqualTo("同步 *** 对账数据");
    }

    @Test
    void reviewReasonContainingSensitiveWordDoesNotRollBackApproval() {
        // 审批理由命中时旧实现会连带回滚已生成的授权，危害最大。
        Throwable thrown = catchThrowable(() -> service.recordSuccess(
                "ACCESS_REQUEST_REVIEWED", 7L, "ACCESS_REQUEST", 9L,
                null, "decision=APPROVED;grantId=3", "给对账用，涉及 token 校验", "exec-2"));

        assertThat(thrown).isNull();
        assertThat(captureInserted().getReason()).isEqualTo("给对账用，涉及 *** 校验");
    }

    @Test
    void overlongReasonIsTruncatedToItsColumnLength() {
        // reason 列是 VARCHAR(500)，而旧实现统一按 2000 判断，
        // 一段较长的申请用途会让 insert 直接失败（Data too long），同样回滚业务。
        service.recordSuccess("ACCESS_REQUEST_SUBMITTED", 2L, "ACCESS_REQUEST", 9L,
                null, null, "用".repeat(600), "exec-1");

        assertThat(captureInserted().getReason()).hasSize(500);
    }

    @Test
    void summariesKeepTheirOwnColumnLength() {
        String longText = "a".repeat(2500);

        service.recordSuccess("DATA_GRANT_CREATED", 2L, "DATA_GRANT", 9L,
                longText, longText, null, "exec-1");

        IamS1AuditEvent saved = captureInserted();
        assertThat(saved.getBeforeSummary()).hasSize(2000);
        assertThat(saved.getAfterSummary()).hasSize(2000);
    }

    @Test
    void normalTextIsWrittenUnchanged() {
        service.recordSuccess("DATA_GRANT_CREATED", 2L, "DATA_GRANT", 9L,
                null, "grantId=1", "临时分析", "exec-1");

        IamS1AuditEvent saved = captureInserted();
        assertThat(saved.getReason()).isEqualTo("临时分析");
        assertThat(saved.getExecutionId()).isEqualTo("exec-1");
        assertThat(saved.getSuccess()).isEqualTo(1);
    }

    @Test
    void failureRecordAlsoMasksAndKeepsColumnLength() {
        service.recordFailure("DATA_GRANT_CREATE_FAILED", 2L, "DATA_GRANT", 9L,
                "同步 database 数据", "x".repeat(800), "exec-3");

        IamS1AuditEvent saved = captureInserted();
        assertThat(saved.getReason()).isEqualTo("同步 *** 数据");
        assertThat(saved.getFailureReason()).hasSize(500);
        assertThat(saved.getSuccess()).isEqualTo(0);
    }

    @Test
    void missingEventOrTargetTypeIsStillRejected() {
        // 程序性错误仍然拒绝：这类参数由代码传入，缺失说明调用点写错了。
        Throwable thrown = catchThrowable(() -> service.recordSuccess(
                "  ", 2L, "DATA_GRANT", 9L, null, null, "ok", "exec-1"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
    }

    private IamS1AuditEvent captureInserted() {
        ArgumentCaptor<IamS1AuditEvent> captor = ArgumentCaptor.forClass(IamS1AuditEvent.class);
        verify(auditEventMapper).insert(captor.capture());
        return captor.getValue();
    }
}
