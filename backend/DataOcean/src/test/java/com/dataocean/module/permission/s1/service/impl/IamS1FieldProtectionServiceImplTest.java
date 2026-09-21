package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.dto.IamS1FieldProtectionSaveDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1AuthorizationDecision;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B2 字段保护写入、保护不授予查询权和审计边界测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IamS1FieldProtectionServiceImplTest {

    @Mock
    private IamS1FieldProtectionMapper protectionMapper;
    @Mock
    private IamS1DatasourceIdentityMapper datasourceMapper;
    @Mock
    private IamS1AuthorizationResolver authorizationResolver;
    @Mock
    private IamS1PermissionRevisionService revisionService;
    @Mock
    private IamS1AuditEventService auditService;
    @Mock
    private IamS1MetadataValidationService metadataService;
    @Mock
    private IamS1PermissionCacheService cacheService;

    private IamS1FieldProtectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IamS1FieldProtectionServiceImpl(protectionMapper, datasourceMapper,
                authorizationResolver, revisionService, auditService, metadataService, cacheService);
        when(datasourceMapper.countEnabledDatasource(1L)).thenReturn(1L);
        when(authorizationResolver.resolveAdminAction(1L, "security:mask:manage", 1L))
                .thenReturn(IamS1AuthorizationDecision.allow("security:mask:manage", 1L));
        when(metadataService.requireColumn(anyLong(), anyLong(), any(), anyLong(), any()))
                .thenReturn(column());
        when(revisionService.record(any(), any(), any(), any(), any())).thenReturn(50L);
    }

    @Test
    void saveMaskedProtectionStoresPolicyAndDoesNotGrantAccess() {
        IamS1FieldProtectionSaveDTO request = request(IamS1Constants.PROTECTION_MASKED, "PHONE");
        doAnswer(invocation -> {
            IamS1FieldProtection protection = invocation.getArgument(0);
            protection.setId(81L);
            return 1;
        }).when(protectionMapper).insert(any(IamS1FieldProtection.class));
        assertThat(service.saveProtection(1L, request)).isEqualTo(81L);
        verify(protectionMapper).insert(any(IamS1FieldProtection.class));
        verify(cacheService).invalidateAfterCommit(1L);
    }

    @Test
    void hiddenProtectionRequiresNoMaskPolicy() {
        IamS1FieldProtectionSaveDTO request = request(IamS1Constants.PROTECTION_HIDDEN, "PHONE");
        assertThatThrownBy(() -> service.saveProtection(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能携带");
        verify(protectionMapper, never()).insert(any(IamS1FieldProtection.class));
    }

    @Test
    void maskedProtectionRequiresKnownPolicy() {
        IamS1FieldProtectionSaveDTO request = request(IamS1Constants.PROTECTION_MASKED, "CUSTOM");
        assertThatThrownBy(() -> service.saveProtection(1L, request))
                .isInstanceOf(BusinessException.class).hasMessageContaining("安全掩码");
    }

    @Test
    void unknownProtocolAndUnauthorizedOperatorAreRejected() {
        IamS1FieldProtectionSaveDTO request = request(IamS1Constants.PROTECTION_HIDDEN, null);
        request.setProtocolVersion("IAM-SIMPLE-0");
        assertThatThrownBy(() -> service.saveProtection(1L, request)).isInstanceOf(BusinessException.class);
        request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        when(authorizationResolver.resolveAdminAction(1L, "security:mask:manage", 1L))
                .thenReturn(IamS1AuthorizationDecision.deny("FUNCTION_NOT_GRANTED", "security:mask:manage", 1L));
        assertThatThrownBy(() -> service.saveProtection(1L, request)).isInstanceOf(BusinessException.class);
    }

    @Test
    void revokeWritesRevisionAndInvalidatesAfterCommit() {
        IamS1FieldProtection protection = new IamS1FieldProtection();
        protection.setId(81L);
        protection.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        protection.setDatasourceId(1L);
        protection.setStatus("ACTIVE");
        when(protectionMapper.selectById(81L)).thenReturn(protection);
        service.revokeProtection(1L, 81L, "撤销测试");
        assertThat(protection.getStatus()).isEqualTo("REVOKED");
        verify(protectionMapper).updateById(protection);
        verify(cacheService).invalidateAfterCommit(1L);
    }

    private IamS1FieldProtectionSaveDTO request(String level, String policy) {
        IamS1FieldProtectionSaveDTO request = new IamS1FieldProtectionSaveDTO();
        request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        request.setDatasourceId(1L);
        request.setMetadataSnapshotId(88L);
        request.setTableName("orders");
        request.setColumnMetaId(101L);
        request.setColumnName("phone");
        request.setProtectionLevel(level);
        request.setMaskPolicy(policy);
        request.setStatus("ACTIVE");
        request.setReason("字段保护测试");
        return request;
    }

    private IamS1ColumnFact column() {
        IamS1ColumnFact column = new IamS1ColumnFact();
        column.setId(101L);
        column.setTableName("orders");
        column.setColumnName("phone");
        column.setDataType("VARCHAR(30)");
        column.setGovernanceStatus("NORMAL");
        return column;
    }
}
