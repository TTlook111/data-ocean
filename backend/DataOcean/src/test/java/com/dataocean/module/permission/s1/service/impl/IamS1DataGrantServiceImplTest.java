package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DataGrantColumn;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1UserIdentity;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantColumnDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataGrantSaveDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1RowConditionDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantColumnMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DepartmentMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import com.dataocean.module.permission.s1.service.IamS1AuditEventService;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1PermissionRevisionService;
import com.dataocean.module.permission.s1.support.IamS1MetadataValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B2 数据授权写服务安全边界与事务编排测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IamS1DataGrantServiceImplTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 17, 9, 0);

    @Mock
    private IamS1DataGrantMapper grantMapper;
    @Mock
    private IamS1DataGrantColumnMapper grantColumnMapper;
    @Mock
    private IamS1RowConditionMapper conditionMapper;
    @Mock
    private IamS1UserIdentityMapper userIdentityMapper;
    @Mock
    private IamS1UserRoleMapper userRoleMapper;
    @Mock
    private IamS1RoleMapper roleMapper;
    @Mock
    private IamS1DepartmentMapper departmentMapper;
    @Mock
    private IamS1FieldProtectionMapper fieldProtectionMapper;
    @Mock
    private IamS1AuthorizationResolver authorizationResolver;
    @Mock
    private IamS1PermissionRevisionService revisionService;
    @Mock
    private IamS1AuditEventService auditEventService;
    @Mock
    private IamS1MetadataValidationService metadataValidationService;
    @Mock
    private IamS1PermissionCacheService cacheService;

    private IamS1DataGrantServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IamS1DataGrantServiceImpl(grantMapper, grantColumnMapper, conditionMapper,
                userIdentityMapper, userRoleMapper, roleMapper, departmentMapper, fieldProtectionMapper,
                authorizationResolver, revisionService, auditEventService, metadataValidationService, cacheService);
        when(authorizationResolver.resolveAdminAction(1L, "security:permission:manage", 1L))
                .thenReturn(IamS1AuthorizationDecision.allow("security:permission:manage", 1L));
        when(userIdentityMapper.countExistingUser(7L)).thenReturn(1L);
        when(userIdentityMapper.selectIdentity(1L)).thenReturn(user(1L, null));
        when(userRoleMapper.countActiveByUserAndRole(1L, 7L)).thenReturn(0L);
        when(revisionService.record(any(), any(), any(), any(), any())).thenReturn(42L);
        when(metadataValidationService.requireColumns(anyLong(), anyLong(), any(), anyCollection()))
                .thenReturn(Map.of("order_id", column(101L, "order_id", "BIGINT")));
        when(metadataValidationService.requireColumn(anyLong(), anyLong(), any(), anyLong(), any()))
                .thenReturn(column(102L, "region", "VARCHAR(50)"));
        when(fieldProtectionMapper.selectActiveBySnapshot(any(), anyLong(), anyLong())).thenReturn(List.of());
    }

    @Test
    void createRequiresKnownProtocol() {
        IamS1DataGrantSaveDTO request = request();
        request.setProtocolVersion("IAM-SIMPLE-0");
        assertThatThrownBy(() -> service.createGrant(1L, request)).isInstanceOf(BusinessException.class);
        verify(grantMapper, never()).insert(any(IamS1DataGrant.class));
    }

    @Test
    void createRequiresS1PermissionAndResponsibleDatasource() {
        when(authorizationResolver.resolveAdminAction(1L, "security:permission:manage", 1L))
                .thenReturn(IamS1AuthorizationDecision.deny("FUNCTION_NOT_GRANTED", "security:permission:manage", 1L));
        assertThatThrownBy(() -> service.createGrant(1L, request()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有维护");
    }

    @Test
    void cannotModifyPersonalGrant() {
        IamS1DataGrantSaveDTO request = request();
        request.setSubjectId(1L);
        when(userIdentityMapper.countExistingUser(1L)).thenReturn(1L);
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本人");
    }

    @Test
    void cannotModifyGrantFromOwnRole() {
        IamS1DataGrantSaveDTO request = request();
        request.setSubjectType(IamS1Constants.SUBJECT_ROLE);
        request.setSubjectId(7L);
        IamS1Role role = new IamS1Role();
        role.setId(7L);
        when(roleMapper.selectById(7L)).thenReturn(role);
        when(userRoleMapper.countActiveByUserAndRole(1L, 7L)).thenReturn(1L);
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("所属角色");
    }

    @Test
    void cannotModifyGrantFromCurrentDepartment() {
        IamS1DataGrantSaveDTO request = request();
        request.setSubjectType(IamS1Constants.SUBJECT_DEPARTMENT);
        request.setSubjectId(10L);
        request.setDepartmentScope(IamS1Constants.DEPARTMENT_SCOPE_SELF);
        when(departmentMapper.selectById(10L)).thenReturn(department(10L, 0L));
        when(userIdentityMapper.selectIdentity(1L)).thenReturn(user(1L, 10L));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(10L, 0L)));
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("当前部门");
    }

    @Test
    void emptyAllowFieldsAreRejected() {
        IamS1DataGrantSaveDTO request = request();
        request.setColumns(List.of());
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少一个");
    }

    @Test
    void datasourceDenyCannotCarryFieldsOrConditions() {
        IamS1DataGrantSaveDTO request = request();
        request.setResourceScope(IamS1Constants.RESOURCE_SCOPE_DATASOURCE);
        request.setEffect(IamS1Constants.EFFECT_DENY);
        request.setMetadataSnapshotId(null);
        request.setTableName(null);
        request.setColumns(List.of(new IamS1DataGrantColumnDTO(101L, "order_id")));
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("数据源级");
    }

    @Test
    void denyCannotCarryRowCondition() {
        IamS1DataGrantSaveDTO request = request();
        request.setEffect(IamS1Constants.EFFECT_DENY);
        request.setColumns(List.of());
        request.setRowConditions(List.of(rowCondition()));
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("DENY");
    }

    @Test
    void structuredConditionIsTypedAndStoredWithoutSqlText() {
        IamS1DataGrantSaveDTO request = request();
        request.setRowConditions(List.of(rowCondition()));
        doAnswer(invocation -> {
            IamS1DataGrant grant = invocation.getArgument(0);
            grant.setId(77L);
            return 1;
        }).when(grantMapper).insert(any(IamS1DataGrant.class));
        service.createGrant(1L, request);
        verify(conditionMapper).insert(any(com.dataocean.module.permission.s1.entity.IamS1RowCondition.class));
    }

    @Test
    void oneFieldMayHaveMultipleStructuredPredicates() {
        IamS1DataGrantSaveDTO request = request();
        request.setRowMatchType("ALL");
        request.setRowConditions(List.of(
                new IamS1RowConditionDTO(102L, "region", "GE", "STRING", "\"华东\"", null),
                new IamS1RowConditionDTO(102L, "region", "LT", "STRING", "\"华南\"", null)));
        doAnswer(invocation -> {
            IamS1DataGrant grant = invocation.getArgument(0);
            grant.setId(78L);
            return 1;
        }).when(grantMapper).insert(any(IamS1DataGrant.class));
        service.createGrant(1L, request);
        verify(conditionMapper, org.mockito.Mockito.times(2))
                .insert(any(com.dataocean.module.permission.s1.entity.IamS1RowCondition.class));
    }

    @Test
    void invalidStructuredValueTypeIsRejected() {
        IamS1DataGrantSaveDTO request = request();
        IamS1RowConditionDTO condition = rowCondition();
        condition.setValueType("INTEGER");
        condition.setStructuredValueJson("\"华东\"");
        request.setRowConditions(List.of(condition));
        assertThatThrownBy(() -> service.createGrant(1L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void successfulCreateRecordsRevisionAuditAndInvalidatesAfterCommit() {
        IamS1DataGrantSaveDTO request = request();
        doAnswer(invocation -> {
            IamS1DataGrant grant = invocation.getArgument(0);
            grant.setId(77L);
            return 1;
        }).when(grantMapper).insert(any(IamS1DataGrant.class));
        Long id = service.createGrant(1L, request);
        assertThat(id).isEqualTo(77L);
        verify(revisionService).record("DATA_GRANT", 77L, "CREATE", 1L, request.getReason());
        verify(auditEventService).recordSuccess(eq("DATA_GRANT_CREATED"), eq(1L), eq("DATA_GRANT"), eq(77L),
                eq(null), any(), eq(request.getReason()), any());
        verify(cacheService).invalidateAfterCommit(1L);
    }

    @Test
    void updateReplacesParentChildrenAtomically() {
        IamS1DataGrant current = requestEntity(77L);
        when(grantMapper.selectById(77L)).thenReturn(current);
        IamS1DataGrantSaveDTO request = request();
        doAnswer(invocation -> {
            IamS1DataGrant grant = invocation.getArgument(0);
            grant.setId(77L);
            return 1;
        }).when(grantMapper).updateById(any(IamS1DataGrant.class));
        service.updateGrant(1L, 77L, request);
        verify(grantColumnMapper).deleteByGrantId(77L);
        verify(conditionMapper).deleteByGrantId(77L);
        verify(grantMapper).updateById(any(IamS1DataGrant.class));
    }

    @Test
    void batchWriteRejectsMoreThanTheServerSideLimit() {
        // 回归：批量接口原先没有任何条数上限，@Valid 又不会级联到 List 元素，
        // 一次请求可以写入任意多条授权（每条都推进 revision 并触发缓存失效）。
        // 服务端上限为 100，构造 101 条即可触发。
        List<IamS1DataGrantSaveDTO> requests = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            requests.add(request());
        }

        assertThatThrownBy(() -> service.createGrants(1L, requests, "批量授权"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过");
        verify(auditEventService).recordFailure(eq("DATA_GRANT_BATCH_CREATE_FAILED"), eq(1L), eq("DATA_GRANT"),
                eq(null), eq("批量授权"), any(), any());
    }

    @Test
    void batchWriteCannotBypassPersonalModificationRule() {
        IamS1DataGrantSaveDTO first = request();
        IamS1DataGrantSaveDTO second = request();
        second.setSubjectId(1L);
        when(userIdentityMapper.countExistingUser(1L)).thenReturn(1L);
        assertThatThrownBy(() -> service.createGrants(1L, List.of(first, second), "批量授权"))
                .isInstanceOf(BusinessException.class);
        verify(auditEventService).recordFailure(eq("DATA_GRANT_BATCH_CREATE_FAILED"), eq(1L), eq("DATA_GRANT"),
                eq(null), eq("批量授权"), any(), any());
    }

    private IamS1DataGrantSaveDTO request() {
        IamS1DataGrantSaveDTO request = new IamS1DataGrantSaveDTO();
        request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        request.setSubjectType(IamS1Constants.SUBJECT_USER);
        request.setSubjectId(7L);
        request.setDatasourceId(1L);
        request.setResourceScope(IamS1Constants.RESOURCE_SCOPE_TABLE);
        request.setMetadataSnapshotId(88L);
        request.setTableName("orders");
        request.setEffect(IamS1Constants.EFFECT_ALLOW);
        request.setValidFrom(FROM);
        request.setColumns(List.of(new IamS1DataGrantColumnDTO(101L, "order_id")));
        request.setReason("测试授权");
        return request;
    }

    private IamS1DataGrant requestEntity(Long id) {
        IamS1DataGrant grant = new IamS1DataGrant();
        grant.setId(id);
        grant.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        grant.setSubjectType(IamS1Constants.SUBJECT_USER);
        grant.setSubjectId(7L);
        grant.setDatasourceId(1L);
        grant.setResourceScope(IamS1Constants.RESOURCE_SCOPE_TABLE);
        grant.setMetadataSnapshotId(88L);
        grant.setTableName("orders");
        grant.setEffect(IamS1Constants.EFFECT_ALLOW);
        grant.setValidFrom(FROM);
        grant.setStatus(IamS1Constants.DATA_GRANT_STATUS_ACTIVE);
        return grant;
    }

    private IamS1RowConditionDTO rowCondition() {
        return new IamS1RowConditionDTO(102L, "region", "EQ", "STRING", "\"华东\"", null);
    }

    private IamS1ColumnFact column(Long id, String name, String type) {
        IamS1ColumnFact fact = new IamS1ColumnFact();
        fact.setId(id);
        fact.setTableName("orders");
        fact.setColumnName(name);
        fact.setDataType(type);
        fact.setGovernanceStatus("NORMAL");
        return fact;
    }

    private IamS1UserIdentity user(Long id, Long departmentId) {
        IamS1UserIdentity user = new IamS1UserIdentity();
        user.setId(id);
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        return user;
    }

    private com.dataocean.module.permission.s1.entity.IamS1DepartmentNode department(Long id, Long parentId) {
        com.dataocean.module.permission.s1.entity.IamS1DepartmentNode node = new com.dataocean.module.permission.s1.entity.IamS1DepartmentNode();
        node.setId(id);
        node.setParentId(parentId);
        node.setStatus(1);
        return node;
    }
}
