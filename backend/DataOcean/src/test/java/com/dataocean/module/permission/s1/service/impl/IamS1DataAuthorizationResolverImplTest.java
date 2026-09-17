package com.dataocean.module.permission.s1.service.impl;

import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.entity.IamS1ColumnFact;
import com.dataocean.module.permission.s1.entity.IamS1DataGrant;
import com.dataocean.module.permission.s1.entity.IamS1DataGrantColumn;
import com.dataocean.module.permission.s1.entity.IamS1DepartmentNode;
import com.dataocean.module.permission.s1.entity.IamS1DatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1FieldProtection;
import com.dataocean.module.permission.s1.entity.IamS1MetadataSnapshotFact;
import com.dataocean.module.permission.s1.entity.IamS1RowCondition;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.IamS1TableFact;
import com.dataocean.module.permission.s1.entity.IamS1UserIdentity;
import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.dto.IamS1TableRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantColumnMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DataGrantMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DatasourceIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1DepartmentMapper;
import com.dataocean.module.permission.s1.mapper.IamS1FieldProtectionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1MetadataResourceMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RowConditionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserIdentityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1UserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** B2 Resolver 聚焦测试：只使用 S1 Mapper，不接触旧权限事实。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IamS1DataAuthorizationResolverImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 10, 0);

    @Mock
    private IamS1UserIdentityMapper userIdentityMapper;
    @Mock
    private IamS1DepartmentMapper departmentMapper;
    @Mock
    private IamS1DatasourceIdentityMapper datasourceIdentityMapper;
    @Mock
    private IamS1RoleMapper roleMapper;
    @Mock
    private IamS1UserRoleMapper userRoleMapper;
    @Mock
    private IamS1MetadataResourceMapper metadataResourceMapper;
    @Mock
    private IamS1DataGrantMapper dataGrantMapper;
    @Mock
    private IamS1DataGrantColumnMapper dataGrantColumnMapper;
    @Mock
    private IamS1RowConditionMapper rowConditionMapper;
    @Mock
    private IamS1FieldProtectionMapper fieldProtectionMapper;
    @Mock
    private IamS1PermissionRevisionMapper permissionRevisionMapper;
    @Mock
    private IamS1PermissionCacheService permissionCacheService;

    private IamS1DataAuthorizationResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new IamS1DataAuthorizationResolverImpl(userIdentityMapper, departmentMapper,
                datasourceIdentityMapper, roleMapper, userRoleMapper, metadataResourceMapper,
                dataGrantMapper, dataGrantColumnMapper, rowConditionMapper, fieldProtectionMapper,
                permissionRevisionMapper, permissionCacheService);
        IamS1UserIdentity user = user(7L, null, 1);
        IamS1DatasourceFact datasource = datasource(1L, 1);
        IamS1MetadataSnapshotFact snapshot = new IamS1MetadataSnapshotFact();
        snapshot.setId(88L);
        snapshot.setDatasourceId(1L);
        snapshot.setStatus("PUBLISHED");
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user);
        when(datasourceIdentityMapper.selectIdentity(1L)).thenReturn(datasource);
        when(permissionRevisionMapper.selectCurrentRevision()).thenReturn(9L);
        when(metadataResourceMapper.selectSnapshot(88L, 1L)).thenReturn(snapshot);
        when(metadataResourceMapper.selectTable(88L, 1L, "orders")).thenReturn(table("orders"));
        when(metadataResourceMapper.selectTable(88L, 1L, "customers")).thenReturn(table("customers"));
        when(metadataResourceMapper.selectColumns(88L, 1L, "orders")).thenReturn(orderColumns());
        when(metadataResourceMapper.selectColumns(88L, 1L, "customers")).thenReturn(customerColumns());
        when(dataGrantMapper.selectActiveByDatasource(IamS1Constants.PROTOCOL_VERSION, 1L)).thenReturn(List.of());
        when(dataGrantColumnMapper.selectByGrantIds(anyCollection())).thenReturn(List.of());
        when(rowConditionMapper.selectByGrantIds(anyCollection())).thenReturn(List.of());
        when(fieldProtectionMapper.selectActiveBySnapshot(IamS1Constants.PROTOCOL_VERSION, 1L, 88L))
                .thenReturn(List.of());
        when(userRoleMapper.selectActiveByUserId(7L)).thenReturn(List.of());
        when(permissionCacheService.read(anyString(), eq(IamS1DataAuthorizationSnapshot.class))).thenReturn(null);
    }

    @Test
    void noAllowDefaultsToDeny() {
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isFalse();
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("NO_ALLOW_COVERING_FIELDS");
    }

    @Test
    void emptyFieldsAreNotAllFields() {
        IamS1DataAuthorizationRequestDTO request = request();
        assertThat(resolver.resolve(request).getReasonCode()).isEqualTo("EMPTY_RESOURCE_COLUMNS");
    }

    @Test
    void unknownProtocolDefaultsToDeny() {
        IamS1DataAuthorizationRequestDTO request = request("order_id");
        request.setProtocolVersion("IAM-SIMPLE-0");
        assertThat(resolver.resolve(request).getReasonCode()).isEqualTo("UNKNOWN_PROTOCOL");
    }

    @Test
    void disabledUserIsDenied() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, null, 0));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("USER_NOT_ENABLED");
    }

    @Test
    void disabledDatasourceIsDenied() {
        when(datasourceIdentityMapper.selectIdentity(1L)).thenReturn(datasource(1L, 0));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("DATASOURCE_NOT_ENABLED");
    }

    @Test
    void oldPermissionFactsCannotGrantWithoutS1Grant() {
        IamS1DataAuthorizationSnapshot result = resolver.resolve(request("order_id"));
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getTables()).hasSize(1);
    }

    @Test
    void systemAdminWithoutBusinessGrantIsDenied() {
        // Resolver never calls the B1 admin bypass; a system administrator still needs a data grant.
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isFalse();
    }

    @Test
    void userGrantTakesEffect() {
        IamS1DataGrant grant = allowGrant(100L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(100L, 101L));
        IamS1DataAuthorizationSnapshot result = resolver.resolve(request("order_id"));
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getTables().get(0).getAllowedColumns()).containsExactly("order_id");
    }

    @Test
    void roleGrantRequiresActiveS1Binding() {
        IamS1DataGrant grant = allowGrant(101L, IamS1Constants.SUBJECT_ROLE, 20L, "order_id");
        IamS1UserRole binding = new IamS1UserRole();
        binding.setRoleId(20L);
        binding.setStatus(1);
        IamS1Role role = new IamS1Role();
        role.setId(20L);
        role.setStatus(1);
        when(userRoleMapper.selectActiveByUserId(7L)).thenReturn(List.of(binding));
        when(roleMapper.selectById(20L)).thenReturn(role);
        stubGrants(List.of(grant), columns(101L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isTrue();
    }

    @Test
    void disabledRoleBindingDoesNotGrant() {
        IamS1DataGrant grant = allowGrant(102L, IamS1Constants.SUBJECT_ROLE, 20L, "order_id");
        IamS1UserRole binding = new IamS1UserRole();
        binding.setRoleId(20L);
        binding.setStatus(0);
        when(userRoleMapper.selectActiveByUserId(7L)).thenReturn(List.of(binding));
        stubGrants(List.of(grant), columns(102L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isFalse();
    }

    @Test
    void departmentSelfMatchesOnlyCurrentDepartment() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 10L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(10L, 0L, 1)));
        IamS1DataGrant grant = allowGrant(103L, IamS1Constants.SUBJECT_DEPARTMENT, 10L, "order_id");
        grant.setDepartmentScope(IamS1Constants.DEPARTMENT_SCOPE_SELF);
        stubGrants(List.of(grant), columns(103L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isTrue();
    }

    @Test
    void departmentIncludeDescendantsMatchesCurrentPath() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 11L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(11L, 10L, 1), department(10L, 0L, 1)));
        IamS1DataGrant grant = allowGrant(104L, IamS1Constants.SUBJECT_DEPARTMENT, 10L, "order_id");
        grant.setDepartmentScope(IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS);
        stubGrants(List.of(grant), columns(104L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isTrue();
    }

    @Test
    void disabledDepartmentPathDeniesInheritance() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 11L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(11L, 10L, 1), department(10L, 0L, 0)));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("DEPARTMENT_PATH_INVALID");
    }

    @Test
    void departmentCycleDefaultsToDeny() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 11L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(11L, 10L, 1), department(10L, 11L, 1)));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("DEPARTMENT_PATH_INVALID");
    }

    @Test
    void departmentBrokenChainDefaultsToDeny() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 11L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(11L, 99L, 1)));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("DEPARTMENT_PATH_INVALID");
    }

    @Test
    void changingMainDepartmentIsUsedOnNextCalculation() {
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 10L, 1), user(7L, 20L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(10L, 0L, 1), department(20L, 0L, 1)));
        IamS1DataGrant east = allowGrant(105L, IamS1Constants.SUBJECT_DEPARTMENT, 10L, "order_id");
        east.setDepartmentScope(IamS1Constants.DEPARTMENT_SCOPE_SELF);
        IamS1DataGrant south = allowGrant(106L, IamS1Constants.SUBJECT_DEPARTMENT, 20L, "order_id");
        south.setDepartmentScope(IamS1Constants.DEPARTMENT_SCOPE_SELF);
        when(dataGrantMapper.selectActiveByDatasource(IamS1Constants.PROTOCOL_VERSION, 1L))
                .thenReturn(List.of(east), List.of(south));
        when(dataGrantColumnMapper.selectByGrantIds(anyCollection())).thenReturn(columns(105L, 101L), columns(106L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isTrue();
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isTrue();
    }

    @Test
    void oneGrantMustCoverAllReferencedFields() {
        IamS1DataGrant grant = allowGrant(107L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(107L, 101L));
        assertThat(resolver.resolve(request("order_id", "amount")).getReasonCode())
                .isEqualTo("NO_ALLOW_COVERING_FIELDS");
    }

    @Test
    void fieldsAndConditionsFromDifferentGrantsCannotBeCrossMultiplied() {
        IamS1DataGrant first = allowGrant(108L, IamS1Constants.SUBJECT_USER, 7L, "amount");
        IamS1DataGrant second = allowGrant(109L, IamS1Constants.SUBJECT_USER, 7L, "name");
        stubGrants(List.of(first, second), columns(108L, 103L, 109L, 104L));
        assertThat(resolver.resolve(request("amount", "name")).isAllowed()).isFalse();
    }

    @Test
    void sameFieldsAllowConditionsAreRetainedAndOrMerged() {
        IamS1DataGrant east = allowGrant(110L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1DataGrant south = allowGrant(111L, IamS1Constants.SUBJECT_DEPARTMENT, 10L, "order_id");
        south.setDepartmentScope(IamS1Constants.DEPARTMENT_SCOPE_SELF);
        when(userIdentityMapper.selectIdentity(7L)).thenReturn(user(7L, 10L, 1));
        when(departmentMapper.selectAll()).thenReturn(List.of(department(10L, 0L, 1)));
        stubGrants(List.of(east, south), columns(110L, 101L, 111L, 101L));
        when(rowConditionMapper.selectByGrantIds(anyCollection())).thenReturn(List.of(condition(110L, 1101L, "华东"),
                condition(111L, 1111L, "华南")));
        IamS1TablePermissionVO table = resolver.resolve(request("order_id")).getTables().get(0);
        assertThat(table.isAllowed()).isTrue();
        assertThat(table.getGrantSources()).hasSize(2);
        assertThat(table.getGrantSources().get(0).getRowCondition().getPredicates()).hasSize(1);
        assertThat(table.getGrantSources().get(1).getRowCondition().getPredicates()).hasSize(1);
    }

    @Test
    void multipleTablesAreCalculatedIndependently() {
        IamS1DataGrant orders = allowGrant(112L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(orders), columns(112L, 101L));
        IamS1DataAuthorizationRequestDTO request = request("order_id");
        request.getTables().add(new IamS1TableRequestDTO("customers", Set.of("customer_id")));
        assertThat(resolver.resolve(request).isAllowed()).isFalse();
        assertThat(resolver.resolve(request).getTables()).hasSize(2);
    }

    @Test
    void duplicateTableRequestsAreRejectedInsteadOfMergingFirstEntry() {
        IamS1DataAuthorizationRequestDTO request = baseRequest();
        request.setTables(List.of(new IamS1TableRequestDTO("orders", Set.of("order_id")),
                new IamS1TableRequestDTO("orders", Set.of("phone"))));
        assertThat(resolver.resolve(request).getReasonCode()).isEqualTo("DUPLICATE_TABLE_REQUEST");
    }

    @Test
    void datasourceDenyOverridesAllow() {
        IamS1DataGrant allow = allowGrant(113L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1DataGrant deny = grant(114L, IamS1Constants.SUBJECT_USER, 7L, IamS1Constants.EFFECT_DENY);
        deny.setResourceScope(IamS1Constants.RESOURCE_SCOPE_DATASOURCE);
        deny.setTableName(null);
        deny.setMetadataSnapshotId(null);
        stubGrants(List.of(allow, deny), columns(113L, 101L));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("DATASOURCE_DENY");
    }

    @Test
    void tableDenyOverridesAllow() {
        IamS1DataGrant allow = allowGrant(115L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1DataGrant deny = grant(116L, IamS1Constants.SUBJECT_USER, 7L, IamS1Constants.EFFECT_DENY);
        stubGrants(List.of(allow, deny), columns(115L, 101L));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("TABLE_DENY");
    }

    @Test
    void fieldDenyAffectsOnlyReferencedField() {
        IamS1DataGrant allow = allowGrant(117L, IamS1Constants.SUBJECT_USER, 7L, "order_id", "amount");
        IamS1DataGrant deny = grant(118L, IamS1Constants.SUBJECT_USER, 7L, IamS1Constants.EFFECT_DENY);
        stubGrants(List.of(allow, deny), columns(117L, 101L, 117L, 103L, 118L, 103L));
        assertThat(resolver.resolve(request("order_id", "amount")).getReasonCode()).isEqualTo("FIELD_DENY");
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isTrue();
    }

    @Test
    void denyAlwaysHasPriorityOverAllow() {
        IamS1DataGrant allow = allowGrant(119L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1DataGrant deny = grant(120L, IamS1Constants.SUBJECT_USER, 7L, IamS1Constants.EFFECT_DENY);
        stubGrants(List.of(allow, deny), columns(119L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isFalse();
    }

    @Test
    void denyCannotHaveRowCondition() {
        IamS1DataGrant deny = grant(121L, IamS1Constants.SUBJECT_USER, 7L, IamS1Constants.EFFECT_DENY);
        IamS1RowCondition row = condition(121L, 1211L, "华东");
        stubGrants(List.of(deny), List.of(), List.of(row));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("DENY_ROW_CONDITION_FORBIDDEN");
    }

    @Test
    void newlyAddedFieldIsNotAutomaticallyIncluded() {
        IamS1DataGrant grant = allowGrant(122L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(122L, 101L));
        assertThat(resolver.resolve(request("order_id", "name")).isAllowed()).isFalse();
    }

    @Test
    void persistedOperatorMustBeWhitelisted() {
        IamS1DataGrant grant = allowGrant(123L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(123L, 101L), List.of(condition(123L, 1231L, "华东", "LIKE")));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("INVALID_ROW_CONDITION");
    }

    @Test
    void invalidConditionTypeOrValueIsDenied() {
        IamS1DataGrant grant = allowGrant(124L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1RowCondition row = condition(124L, 1241L, "not-integer");
        row.setColumnMetaId(101L);
        row.setColumnName("order_id");
        row.setValueType("INTEGER");
        row.setStructuredValueJson("\"not-an-integer\"");
        stubGrants(List.of(grant), columns(124L, 101L), List.of(row));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("INVALID_ROW_CONDITION");
    }

    @Test
    void handWrittenSqlFunctionAndSubqueryCannotBecomeCondition() {
        IamS1DataGrant grant = allowGrant(125L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1RowCondition row = condition(125L, 1251L, "(SELECT secret FROM users)", "EQ");
        row.setStructuredValueJson("region = '华东'");
        stubGrants(List.of(grant), columns(125L, 101L), List.of(row));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("INVALID_ROW_CONDITION");
    }

    @Test
    void hiddenFieldIsDenied() {
        IamS1DataGrant grant = allowGrant(126L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection hidden = protection(1261L, 105L, "phone", IamS1Constants.PROTECTION_HIDDEN, null);
        stubGrants(List.of(grant), columns(126L, 105L), List.of(), List.of(hidden));
        assertThat(resolver.resolve(request("phone")).getReasonCode()).isEqualTo("FIELD_HIDDEN");
    }

    @Test
    void maskedFieldMayBeProjectedDirectly() {
        IamS1DataGrant grant = allowGrant(127L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection masked = protection(1271L, 105L, "phone", IamS1Constants.PROTECTION_MASKED, "PHONE");
        stubGrants(List.of(grant), columns(127L, 105L), List.of(), List.of(masked));
        IamS1TableRequestDTO table = new IamS1TableRequestDTO("orders", Set.of("phone"));
        table.setColumnUsages(Map.of("phone", Set.of(IamS1ColumnUsage.PROJECTION)));
        assertThat(resolver.resolve(request(table)).isAllowed()).isTrue();
    }

    @Test
    void maskedFieldRequiresExplicitProjectionUsageWhenUsageIsMissingEmptyOrNull() {
        IamS1DataGrant grant = allowGrant(137L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection masked = protection(1371L, 105L, "phone", IamS1Constants.PROTECTION_MASKED, "PHONE");
        stubGrants(List.of(grant), columns(137L, 105L), List.of(), List.of(masked));
        assertThat(resolver.resolve(request("phone")).getReasonCode()).isEqualTo("MASKED_FIELD_USAGE_FORBIDDEN");
        IamS1TableRequestDTO emptyUsage = new IamS1TableRequestDTO("orders", Set.of("phone"));
        emptyUsage.setColumnUsages(Map.of("phone", Set.of()));
        assertThat(resolver.resolve(request(emptyUsage)).getReasonCode()).isEqualTo("MASKED_FIELD_USAGE_FORBIDDEN");
        IamS1TableRequestDTO nullUsage = new IamS1TableRequestDTO("orders", Set.of("phone"));
        Map<String, Set<IamS1ColumnUsage>> nullUsageMap = new HashMap<>();
        nullUsageMap.put("phone", null);
        nullUsage.setColumnUsages(nullUsageMap);
        assertThat(resolver.resolve(request(nullUsage)).getReasonCode()).isEqualTo("MASKED_FIELD_USAGE_FORBIDDEN");
    }

    @Test
    void maskedFieldCannotBeUsedForFilterJoinOrderGroupOrFunction() {
        IamS1DataGrant grant = allowGrant(128L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection masked = protection(1281L, 105L, "phone", IamS1Constants.PROTECTION_MASKED, "PHONE");
        stubGrants(List.of(grant), columns(128L, 105L), List.of(), List.of(masked));
        IamS1TableRequestDTO table = new IamS1TableRequestDTO("orders", Set.of("phone"));
        table.setColumnUsages(Map.of("phone", Set.of(IamS1ColumnUsage.FILTER)));
        IamS1DataAuthorizationRequestDTO request = request(table);
        assertThat(resolver.resolve(request).getReasonCode()).isEqualTo("MASKED_FIELD_USAGE_FORBIDDEN");
    }

    @Test
    void conflictingProtectionPoliciesEscalateToHidden() {
        IamS1DataGrant grant = allowGrant(129L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection first = protection(1291L, 105L, "phone", IamS1Constants.PROTECTION_MASKED, "PHONE");
        IamS1FieldProtection second = protection(1292L, 105L, "phone", IamS1Constants.PROTECTION_MASKED, "EMAIL");
        stubGrants(List.of(grant), columns(129L, 105L), List.of(), List.of(first, second));
        assertThat(resolver.resolve(request("phone")).getReasonCode()).isEqualTo("FIELD_HIDDEN");
    }

    @Test
    void unknownProtectionLevelEscalatesToHidden() {
        IamS1DataGrant grant = allowGrant(138L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection unknown = protection(1381L, 105L, "phone", "NEW_PROTECTION_LEVEL", null);
        stubGrants(List.of(grant), columns(138L, 105L), List.of(), List.of(unknown));
        assertThat(resolver.resolve(request("phone")).getReasonCode()).isEqualTo("FIELD_HIDDEN");
    }

    @Test
    void maskedProtectionWithMissingOrUnknownPolicyEscalatesToHidden() {
        IamS1DataGrant grant = allowGrant(139L, IamS1Constants.SUBJECT_USER, 7L, "phone");
        IamS1FieldProtection missingPolicy = protection(1391L, 105L, "phone",
                IamS1Constants.PROTECTION_MASKED, null);
        stubGrants(List.of(grant), columns(139L, 105L), List.of(), List.of(missingPolicy));
        assertThat(resolver.resolve(request("phone")).getReasonCode()).isEqualTo("FIELD_HIDDEN");

        IamS1FieldProtection unknownPolicy = protection(1392L, 105L, "phone",
                IamS1Constants.PROTECTION_MASKED, "UNKNOWN_POLICY");
        stubGrants(List.of(grant), columns(139L, 105L), List.of(), List.of(unknownPolicy));
        assertThat(resolver.resolve(request("phone")).getReasonCode()).isEqualTo("FIELD_HIDDEN");
    }

    @Test
    void previewUsesExactlyTheResolverResult() {
        IamS1DataGrant grant = allowGrant(130L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(130L, 101L));
        IamS1DataAuthorizationSnapshot resolved = resolver.resolve(request("order_id"));
        IamS1DataAuthorizationSnapshot preview = resolver.preview(request("order_id"));
        assertThat(preview.isAllowed()).isEqualTo(resolved.isAllowed());
        assertThat(preview.getReasonCode()).isEqualTo(resolved.getReasonCode());
        assertThat(preview.getTables().get(0).getGrantSources()).hasSize(resolved.getTables().get(0).getGrantSources().size());
    }

    @Test
    void revisionIsStableAndAppearsInCacheKey() {
        IamS1DataGrant grant = allowGrant(131L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(131L, 101L));
        assertThat(resolver.resolve(request("order_id")).getPermissionRevision()).isEqualTo(9L);
        assertThat(resolver.resolve(request("order_id")).getPermissionRevision()).isEqualTo(9L);
        org.mockito.Mockito.verify(permissionCacheService, org.mockito.Mockito.atLeastOnce())
                .read(org.mockito.ArgumentMatchers.contains(":9:"), eq(IamS1DataAuthorizationSnapshot.class));
    }

    @Test
    void futureBoundaryIsTheNearestGrantBoundary() {
        IamS1DataGrant grant = allowGrant(132L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        grant.setValidFrom(NOW.plusHours(8));
        grant.setValidUntil(NOW.plusHours(12));
        stubGrants(List.of(grant), columns(132L, 101L));
        assertThat(resolver.resolve(request("order_id")).getNextEffectiveAt()).isEqualTo(NOW.plusHours(8));
    }

    @Test
    void noFutureBoundaryUsesNull() {
        IamS1DataGrant grant = allowGrant(133L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        stubGrants(List.of(grant), columns(133L, 101L));
        assertThat(resolver.resolve(request("order_id")).getNextEffectiveAt()).isNull();
    }

    @Test
    void expiredGrantIsImmediatelyIneffective() {
        IamS1DataGrant grant = allowGrant(134L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        grant.setValidFrom(NOW.minusHours(2));
        grant.setValidUntil(NOW.minusHours(1));
        stubGrants(List.of(grant), columns(134L, 101L));
        assertThat(resolver.resolve(request("order_id")).isAllowed()).isFalse();
    }

    @Test
    void invalidGrantFactFailsClosed() {
        IamS1DataGrant grant = allowGrant(135L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        grant.setEffect("UNKNOWN");
        stubGrants(List.of(grant), columns(135L, 101L));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("INVALID_GRANT");
    }

    @Test
    void maskedConditionFieldIsDeniedEvenWhenNotProjected() {
        IamS1DataGrant grant = allowGrant(136L, IamS1Constants.SUBJECT_USER, 7L, "order_id");
        IamS1FieldProtection masked = protection(1361L, 102L, "region", IamS1Constants.PROTECTION_MASKED, "NAME");
        stubGrants(List.of(grant), columns(136L, 101L), List.of(condition(136L, 1361L, "华东")), List.of(masked));
        assertThat(resolver.resolve(request("order_id")).getReasonCode()).isEqualTo("INVALID_ROW_CONDITION");
    }

    private void stubGrants(List<IamS1DataGrant> grants, List<IamS1DataGrantColumn> columns) {
        stubGrants(grants, columns, List.of(), List.of());
    }

    private void stubGrants(List<IamS1DataGrant> grants, List<IamS1DataGrantColumn> columns,
                            List<IamS1RowCondition> conditions) {
        stubGrants(grants, columns, conditions, List.of());
    }

    private void stubGrants(List<IamS1DataGrant> grants, List<IamS1DataGrantColumn> columns,
                            List<IamS1RowCondition> conditions, List<IamS1FieldProtection> protections) {
        when(dataGrantMapper.selectActiveByDatasource(IamS1Constants.PROTOCOL_VERSION, 1L)).thenReturn(grants);
        when(dataGrantColumnMapper.selectByGrantIds(anyCollection())).thenReturn(columns);
        when(rowConditionMapper.selectByGrantIds(anyCollection())).thenReturn(conditions);
        when(fieldProtectionMapper.selectActiveBySnapshot(IamS1Constants.PROTOCOL_VERSION, 1L, 88L))
                .thenReturn(protections);
    }

    private IamS1DataAuthorizationRequestDTO request(String... columns) {
        return request(new IamS1TableRequestDTO("orders", Set.of(columns)));
    }

    private IamS1DataAuthorizationRequestDTO request() {
        IamS1DataAuthorizationRequestDTO request = baseRequest();
        request.setTables(List.of(new IamS1TableRequestDTO("orders", Set.of())));
        return request;
    }

    private IamS1DataAuthorizationRequestDTO request(IamS1TableRequestDTO table) {
        IamS1DataAuthorizationRequestDTO request = baseRequest();
        request.setTables(new java.util.ArrayList<>(List.of(table)));
        return request;
    }

    private IamS1DataAuthorizationRequestDTO baseRequest() {
        IamS1DataAuthorizationRequestDTO request = new IamS1DataAuthorizationRequestDTO();
        request.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        request.setUserId(7L);
        request.setDatasourceId(1L);
        request.setActiveMetadataSnapshotId(88L);
        request.setCalculatedAt(NOW);
        return request;
    }

    private IamS1DataGrant allowGrant(Long id, String subjectType, Long subjectId, String... columns) {
        IamS1DataGrant grant = grant(id, subjectType, subjectId, IamS1Constants.EFFECT_ALLOW);
        return grant;
    }

    private IamS1DataGrant grant(Long id, String subjectType, Long subjectId, String effect) {
        IamS1DataGrant grant = new IamS1DataGrant();
        grant.setId(id);
        grant.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        grant.setSubjectType(subjectType);
        grant.setSubjectId(subjectId);
        grant.setDatasourceId(1L);
        grant.setResourceScope(IamS1Constants.RESOURCE_SCOPE_TABLE);
        grant.setMetadataSnapshotId(88L);
        grant.setTableName("orders");
        grant.setEffect(effect);
        grant.setGrantSource(IamS1Constants.GRANT_SOURCE_MANUAL);
        grant.setValidFrom(NOW.minusDays(1));
        grant.setStatus(IamS1Constants.DATA_GRANT_STATUS_ACTIVE);
        return grant;
    }

    private List<IamS1DataGrantColumn> columns(Object... values) {
        java.util.ArrayList<IamS1DataGrantColumn> result = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            Long grantId = (Long) values[i];
            Long columnId = (Long) values[i + 1];
            IamS1DataGrantColumn column = new IamS1DataGrantColumn();
            column.setGrantId(grantId);
            column.setColumnMetaId(columnId);
            column.setMetadataSnapshotId(88L);
            column.setTableName("orders");
            column.setColumnName(columnName(columnId));
            result.add(column);
        }
        return result;
    }

    private IamS1RowCondition condition(Long grantId, Long id, String value) {
        return condition(grantId, id, value, "EQ");
    }

    private IamS1RowCondition condition(Long grantId, Long id, String value, String operator) {
        IamS1RowCondition row = new IamS1RowCondition();
        row.setId(id);
        row.setGrantId(grantId);
        row.setMetadataSnapshotId(88L);
        row.setTableName("orders");
        row.setMatchType(IamS1Constants.ROW_MATCH_ALL);
        row.setSequenceNo(1);
        row.setColumnMetaId(102L);
        row.setColumnName("region");
        row.setOperatorCode(operator);
        row.setValueType("STRING");
        row.setStructuredValueJson("\"" + value + "\"");
        return row;
    }

    private IamS1FieldProtection protection(Long id, Long columnId, String columnName, String level, String policy) {
        IamS1FieldProtection protection = new IamS1FieldProtection();
        protection.setId(id);
        protection.setProtocolVersion(IamS1Constants.PROTOCOL_VERSION);
        protection.setDatasourceId(1L);
        protection.setMetadataSnapshotId(88L);
        protection.setTableName("orders");
        protection.setColumnMetaId(columnId);
        protection.setColumnName(columnName);
        protection.setProtectionLevel(level);
        protection.setMaskPolicy(policy);
        protection.setStatus("ACTIVE");
        return protection;
    }

    private IamS1UserIdentity user(Long id, Long departmentId, int status) {
        IamS1UserIdentity user = new IamS1UserIdentity();
        user.setId(id);
        user.setDepartmentId(departmentId);
        user.setStatus(status);
        return user;
    }

    private com.dataocean.module.permission.s1.entity.IamS1DatasourceFact datasource(Long id, int status) {
        com.dataocean.module.permission.s1.entity.IamS1DatasourceFact datasource = new com.dataocean.module.permission.s1.entity.IamS1DatasourceFact();
        datasource.setId(id);
        datasource.setName("销售库");
        datasource.setStatus(status);
        datasource.setDeleted(0L);
        return datasource;
    }

    private IamS1DepartmentNode department(Long id, Long parentId, int status) {
        IamS1DepartmentNode node = new IamS1DepartmentNode();
        node.setId(id);
        node.setParentId(parentId);
        node.setStatus(status);
        return node;
    }

    private IamS1TableFact table(String name) {
        IamS1TableFact table = new IamS1TableFact();
        table.setId(name.equals("orders") ? 1L : 2L);
        table.setSnapshotId(88L);
        table.setDatasourceId(1L);
        table.setTableName(name);
        table.setGovernanceStatus("NORMAL");
        return table;
    }

    private List<IamS1ColumnFact> orderColumns() {
        return List.of(column(101L, "order_id", "BIGINT"), column(102L, "region", "VARCHAR(50)"),
                column(103L, "amount", "DECIMAL(10,2)"), column(104L, "name", "VARCHAR(100)"),
                column(105L, "phone", "VARCHAR(30)"));
    }

    private List<IamS1ColumnFact> customerColumns() {
        return List.of(column(201L, "customer_id", "BIGINT"));
    }

    private IamS1ColumnFact column(Long id, String name, String dataType) {
        IamS1ColumnFact column = new IamS1ColumnFact();
        column.setId(id);
        column.setSnapshotId(88L);
        column.setTableMetaId(1L);
        column.setDatasourceId(1L);
        column.setTableName("orders");
        column.setColumnName(name);
        column.setDataType(dataType);
        column.setGovernanceStatus("NORMAL");
        return column;
    }

    private String columnName(Long id) {
        return switch (id.intValue()) {
            case 101 -> "order_id";
            case 102 -> "region";
            case 103 -> "amount";
            case 104 -> "name";
            case 105 -> "phone";
            default -> "column_" + id;
        };
    }
}
