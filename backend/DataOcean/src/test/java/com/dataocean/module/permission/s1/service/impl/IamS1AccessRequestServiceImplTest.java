package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.entity.IamS1AccessRequest;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessRequestSubmitDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1AccessReviewDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AccessRequestVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.mapper.IamS1AccessApprovalMapper;
import com.dataocean.module.permission.s1.mapper.IamS1AccessRequestMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.service.IamS1DataAuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1DataGrantService;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1MetadataValidationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B4：新体系访问申请与审批的服务端约束。 */
class IamS1AccessRequestServiceImplTest {

    @Test
    void submitRequiresQueryUseFunction() {
        Fixture fixture = new Fixture();
        doThrow(new BusinessException(403, "没有 S1 功能：使用问数")).when(fixture.adminGuard)
                .requireGlobalFunction(2L, "query:use");

        Throwable thrown = catchThrowable(() -> fixture.service.submit(2L, submitRequest()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("使用问数");
        verify(fixture.accessRequestMapper, never()).insert(any(IamS1AccessRequest.class));
    }

    @Test
    void submitRejectsRecordScopeOtherThanAll() {
        Fixture fixture = new Fixture();
        IamS1AccessRequestSubmitDTO request = submitRequest();
        request.setRowScope("CONDITION");

        Throwable thrown = catchThrowable(() -> fixture.service.submit(2L, request));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("只支持全部记录");
        verify(fixture.accessRequestMapper, never()).insert(any(IamS1AccessRequest.class));
    }

    @Test
    void submitRejectsDisabledDatasource() {
        Fixture fixture = new Fixture();
        IamS1DatasourceFact datasource = new IamS1DatasourceFact();
        datasource.setId(5L);
        datasource.setName("销售库");
        datasource.setStatus(0);
        when(fixture.datasourceIdentityMapper.selectIdentity(5L)).thenReturn(datasource);

        Throwable thrown = catchThrowable(() -> fixture.service.submit(2L, submitRequest()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("不存在或未启用");
    }

    @Test
    void submitStoresPendingRequestWithoutRowConditionValues() {
        Fixture fixture = new Fixture();
        when(fixture.metadataValidationService.requireColumns(eq(5L), eq(88L), eq("orders"), any()))
                .thenReturn(columnFacts("amount", "region"));
        doAnswer(invocation -> {
            invocation.getArgument(0, IamS1AccessRequest.class).setId(300L);
            return 1;
        }).when(fixture.accessRequestMapper).insert(any(IamS1AccessRequest.class));
        when(fixture.revisionService.record(any(), any(), any(), any(), any())).thenReturn(11L);

        Long requestId = fixture.service.submit(2L, submitRequest());

        assertThat(requestId).isEqualTo(300L);
        verify(fixture.revisionService).record("ACCESS_REQUEST", 300L, "SUBMIT", 2L, "做月度销售分析");
    }

    @Test
    void reviewerCannotApproveOwnRequest() {
        Fixture fixture = new Fixture();
        IamS1AccessRequest request = pendingRequest();
        request.setRequesterId(2L);
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(request);

        Throwable thrown = catchThrowable(() -> fixture.service.review(2L, 300L, approveReview()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("不能审批本人的申请");
        verify(fixture.accessApprovalMapper, never())
                .insert(any(com.dataocean.module.permission.s1.entity.IamS1AccessApproval.class));
    }

    @Test
    void approvedColumnsMustBeSubsetOfRequestedColumns() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        IamS1AccessReviewDTO review = approveReview();
        review.setApprovedColumns(List.of("phone"));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, review));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("必须是申请字段的子集").contains("phone");
    }

    @Test
    void approveRejectsWhenUnifiedResolverReportsApplicableDeny() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        when(fixture.dataAuthorizationResolver.resolve(any())).thenReturn(denySnapshot("TABLE_DENY"));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, approveReview()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("命中禁止规则").contains("该表被禁止查询");
        verify(fixture.dataGrantService, never()).createApprovalGrant(any(), any(), any());
    }

    @Test
    void denyThatDoesNotMatchRequesterDoesNotBlockApproval() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        // 统一 Resolver 已完成主体匹配、有效期与字段判定：没有命中禁止时给出“缺少完整覆盖的允许授权”，
        // 这正是审批要补足的缺口，不得阻断开通。
        when(fixture.dataAuthorizationResolver.resolve(any()))
                .thenReturn(denySnapshot("NO_ALLOW_COVERING_FIELDS"));
        stubApprovalSuccess(fixture);

        Long grantId = fixture.service.review(9L, 300L, approveReview());

        assertThat(grantId).isEqualTo(9001L);
        verify(fixture.dataGrantService).createApprovalGrant(eq(9L), eq(300L), any());
    }

    @Test
    void approveRequiresExplicitExpirySoNoPermanentGrantCanBeCreated() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        IamS1AccessReviewDTO review = approveReview();
        review.setApprovedValidUntil(null);

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, review));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("必须指定批准到期时间");
        verify(fixture.accessApprovalMapper, never())
                .insert(any(com.dataocean.module.permission.s1.entity.IamS1AccessApproval.class));
        verify(fixture.dataGrantService, never()).createApprovalGrant(any(), any(), any());
    }

    @Test
    void approveBeyondSystemLimitIsRejected() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        IamS1AccessReviewDTO review = approveReview();
        review.setApprovedValidUntil(LocalDateTime.now().plusDays(40));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, review));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("系统上限 30 天");
    }

    @Test
    void approveBeyondRequestedWindowIsRejected() {
        Fixture fixture = new Fixture();
        IamS1AccessRequest request = pendingRequest();
        request.setRequestedValidUntil(LocalDateTime.now().plusDays(3));
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(request);
        IamS1AccessReviewDTO review = approveReview();
        review.setApprovedValidUntil(LocalDateTime.now().plusDays(5));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, review));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("不能超过申请时长");
    }

    @Test
    void unknownResolverFailureBlocksApproval() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        // resolve() 会把内部事实读取异常转成拒绝快照而不是抛出：未知原因必须 fail-closed，
        // 不能因为“不是三个明确 DENY 码”就当成没有禁止而放行审批。
        when(fixture.dataAuthorizationResolver.resolve(any())).thenReturn(denySnapshot("FACT_READ_FAILED"));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, approveReview()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("拒绝审批开通").contains("权限事实读取失败");
        verify(fixture.dataGrantService, never()).createApprovalGrant(any(), any(), any());
    }

    @Test
    void brokenDepartmentPathBlocksApproval() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        when(fixture.dataAuthorizationResolver.resolve(any()))
                .thenReturn(denySnapshot("DEPARTMENT_PATH_INVALID"));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, approveReview()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("申请人部门路径无效");
    }

    @Test
    void approvingMaskedColumnDeclaresProjectionOnlyUsage() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        stubApprovalSuccess(fixture);
        IamS1FieldProtection protection = new IamS1FieldProtection();
        protection.setColumnName("amount");
        protection.setProtectionLevel("MASKED");
        protection.setMaskPolicy("PHONE");
        when(fixture.fieldProtectionMapper.selectActiveBySnapshot("IAM-SIMPLE-1", 5L, 88L))
                .thenReturn(List.of(protection));
        when(fixture.dataAuthorizationResolver.resolve(any()))
                .thenReturn(denySnapshot("NO_ALLOW_COVERING_FIELDS"));

        fixture.service.review(9L, 300L, approveReview());

        var captor = org.mockito.ArgumentCaptor.forClass(
                com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO.class);
        verify(fixture.dataAuthorizationResolver).resolve(captor.capture());
        var usages = captor.getValue().getTables().get(0).getColumnUsages();
        // 脱敏字段只能直接投影，否则统一 Resolver 会以 MASKED_FIELD_USAGE_FORBIDDEN 拒绝，
        // 而该原因码不在可容忍白名单内，会误伤“批准脱敏字段”这种合法审批。
        assertThat(usages.get("amount")).containsExactly(
                com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.PROJECTION);
        assertThat(usages.get("region")).containsExactlyInAnyOrder(
                com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.PROJECTION,
                com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.FILTER,
                com.dataocean.module.permission.s1.enums.IamS1ColumnUsage.JOIN);
    }

    private com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot denySnapshot(
            String reasonCode) {
        return com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot.deny(
                reasonCode, null, 1L, "销售库");
    }

    private void stubApprovalSuccess(Fixture fixture) {
        when(fixture.fieldProtectionMapper.selectActiveBySnapshot("IAM-SIMPLE-1", 5L, 88L)).thenReturn(List.of());
        when(fixture.metadataValidationService.requireColumns(eq(5L), eq(88L), eq("orders"), any()))
                .thenReturn(columnFacts("amount", "region"));
        when(fixture.dataGrantService.createApprovalGrant(eq(9L), eq(300L), any())).thenReturn(9001L);
        when(fixture.revisionService.record(any(), any(), any(), any(), any())).thenReturn(12L);
        when(fixture.accessRequestMapper.updateStatusIfPending(eq(300L), eq("APPROVED"), any(), eq(9L)))
                .thenReturn(1);
    }

    @Test
    void approveRejectsHiddenColumn() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        when(fixture.dataAuthorizationResolver.resolve(any()))
                .thenReturn(denySnapshot("NO_ALLOW_COVERING_FIELDS"));
        IamS1FieldProtection protection = new IamS1FieldProtection();
        protection.setColumnName("amount");
        protection.setProtectionLevel("HIDDEN");
        when(fixture.fieldProtectionMapper.selectActiveBySnapshot("IAM-SIMPLE-1", 5L, 88L))
                .thenReturn(List.of(protection));

        Throwable thrown = catchThrowable(() -> fixture.service.review(9L, 300L, approveReview()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("隐藏字段不能审批开通").contains("amount");
    }

    @Test
    void approveGeneratesApprovalGrantAndMarksRequestApproved() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectForUpdate(300L)).thenReturn(pendingRequest());
        when(fixture.dataAuthorizationResolver.resolve(any()))
                .thenReturn(denySnapshot("NO_ALLOW_COVERING_FIELDS"));
        stubApprovalSuccess(fixture);

        Long grantId = fixture.service.review(9L, 300L, approveReview());

        assertThat(grantId).isEqualTo(9001L);
        verify(fixture.dataGrantService).createApprovalGrant(eq(9L), eq(300L), any());
    }

    @Test
    void withdrawRejectsRequestOwnedByAnotherUser() {
        Fixture fixture = new Fixture();
        when(fixture.accessRequestMapper.selectById(300L)).thenReturn(pendingRequest());

        Throwable thrown = catchThrowable(() -> fixture.service.withdraw(7L, 300L, "撤回"));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("只能撤回本人的申请");
        verify(fixture.accessRequestMapper, never()).updateStatusIfPending(any(), any(), any(), any());
    }

    private IamS1AccessRequest pendingRequest() {
        IamS1AccessRequest request = new IamS1AccessRequest();
        request.setId(300L);
        request.setProtocolVersion("IAM-SIMPLE-1");
        request.setRequesterId(2L);
        request.setDatasourceId(5L);
        request.setMetadataSnapshotId(88L);
        request.setTableName("orders");
        request.setRequestedColumnsJson("[\"amount\",\"region\"]");
        request.setRowScope("ALL");
        request.setPurpose("做月度销售分析");
        request.setStatus("PENDING");
        return request;
    }

    private IamS1AccessRequestSubmitDTO submitRequest() {
        IamS1AccessRequestSubmitDTO request = new IamS1AccessRequestSubmitDTO();
        request.setDatasourceId(5L);
        request.setMetadataSnapshotId(88L);
        request.setTableName("orders");
        request.setColumns(List.of("amount", "region"));
        request.setRowScope("ALL");
        request.setPurpose("做月度销售分析");
        return request;
    }

    private IamS1AccessReviewDTO approveReview() {
        IamS1AccessReviewDTO review = new IamS1AccessReviewDTO();
        review.setDecision("APPROVE");
        review.setApprovedValidUntil(LocalDateTime.now().plusDays(7));
        review.setReason("同意临时分析");
        return review;
    }

    private Map<String, IamS1ColumnFact> columnFacts(String... names) {
        Map<String, IamS1ColumnFact> facts = new LinkedHashMap<>();
        long id = 100L;
        for (String name : names) {
            IamS1ColumnFact fact = new IamS1ColumnFact();
            fact.setId(id++);
            fact.setColumnName(name);
            fact.setTableName("orders");
            facts.put(name, fact);
        }
        return facts;
    }

    @Test
    void listQueueRejectsWhenFunctionAndResponsibleScopeAreOnDifferentBindings() {
        Fixture fixture = new Fixture();
        // 同一绑定上没有“查看访问申请 + 负责源”的数据源时必须拒绝：
        // 不得回退成“任意绑定的功能 + 任意绑定的负责源”的交叉相乘，
        // 否则没有审批权的角色能借另一个角色的负责源看到全部申请。
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "security:approval:view"))
                .thenReturn(List.of());

        Throwable thrown = catchThrowable(() -> fixture.service.listQueue(7L, "PENDING", 1, 20));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("同一个角色绑定");
        verify(fixture.accessRequestMapper, never()).selectPage(any(), any());
    }

    @Test
    void listQueueReadsOnlyDatasourcesWhereFunctionAndScopeShareOneBinding() {
        Fixture fixture = new Fixture();
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "security:approval:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "销售库", true)));
        when(fixture.accessRequestMapper.selectPage(any(), any())).thenReturn(new Page<>());

        Page<IamS1AccessRequestVO> result = fixture.service.listQueue(7L, "PENDING", 1, 20);

        assertThat(result.getRecords()).isEmpty();
        verify(fixture.capabilityService).responsibleDatasourcesWithFunction(7L, "security:approval:view");
    }

    @Test
    void listQueuePassesPagingThroughToTheDatabase() {
        // 回归：原实现对每个负责源固定取 100 条再合并，超出部分没有任何入口能看到或处理。
        Fixture fixture = new Fixture();
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "security:approval:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "销售库", true)));
        when(fixture.accessRequestMapper.selectPage(any(), any())).thenReturn(new Page<>());

        fixture.service.listQueue(7L, "HANDLED", 3, 50);

        ArgumentCaptor<Page<IamS1AccessRequest>> captor = pageCaptor();
        verify(fixture.accessRequestMapper).selectPage(captor.capture(), any());
        assertThat(captor.getValue().getCurrent()).isEqualTo(3L);
        assertThat(captor.getValue().getSize()).isEqualTo(50L);
    }

    @Test
    void listQueueRejectsUnknownStatusGroup() {
        Fixture fixture = new Fixture();
        when(fixture.capabilityService.responsibleDatasourcesWithFunction(7L, "security:approval:view"))
                .thenReturn(List.of(new IamS1DatasourceRefVO(5L, "销售库", true)));

        Throwable thrown = catchThrowable(() -> fixture.service.listQueue(7L, "WHATEVER", 1, 20));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(thrown.getMessage()).contains("PENDING");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Page<IamS1AccessRequest>> pageCaptor() {
        return ArgumentCaptor.forClass(Page.class);
    }

    private static final class Fixture {
        private final IamS1AccessRequestMapper accessRequestMapper = mock(IamS1AccessRequestMapper.class);
        private final IamS1AccessApprovalMapper accessApprovalMapper = mock(IamS1AccessApprovalMapper.class);
        private final IamS1DataGrantMapper dataGrantMapper = mock(IamS1DataGrantMapper.class);
        private final IamS1FieldProtectionMapper fieldProtectionMapper = mock(IamS1FieldProtectionMapper.class);
        private final IamS1UserIdentityMapper userIdentityMapper = mock(IamS1UserIdentityMapper.class);
        private final IamS1DatasourceIdentityMapper datasourceIdentityMapper =
                mock(IamS1DatasourceIdentityMapper.class);
        private final IamS1SubjectQueryMapper subjectQueryMapper = mock(IamS1SubjectQueryMapper.class);
        private final IamS1MetadataValidationService metadataValidationService =
                mock(IamS1MetadataValidationService.class);
        private final IamS1CapabilityService capabilityService = mock(IamS1CapabilityService.class);
        private final IamS1DataGrantService dataGrantService = mock(IamS1DataGrantService.class);
        private final IamS1PermissionRevisionService revisionService =
                mock(IamS1PermissionRevisionService.class);
        private final IamS1AuditEventService auditEventService = mock(IamS1AuditEventService.class);
        private final IamS1AdminGuard adminGuard = mock(IamS1AdminGuard.class);
        private final IamS1DataAuthorizationResolver dataAuthorizationResolver =
                mock(IamS1DataAuthorizationResolver.class);
        private final IamS1AccessRequestServiceImpl service = new IamS1AccessRequestServiceImpl(
                accessRequestMapper, accessApprovalMapper, dataGrantMapper, fieldProtectionMapper,
                userIdentityMapper, datasourceIdentityMapper, subjectQueryMapper, metadataValidationService,
                capabilityService, dataGrantService, revisionService, auditEventService, adminGuard,
                dataAuthorizationResolver);

        {
            IamS1DatasourceFact enabled = new IamS1DatasourceFact();
            enabled.setId(5L);
            enabled.setName("销售库");
            enabled.setStatus(1);
            when(datasourceIdentityMapper.selectIdentity(5L)).thenReturn(enabled);
        }
    }
}
