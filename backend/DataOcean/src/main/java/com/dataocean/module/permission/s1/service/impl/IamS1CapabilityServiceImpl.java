package com.dataocean.module.permission.s1.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import com.dataocean.module.permission.s1.entity.IamS1ResponsibleDatasourceFact;
import com.dataocean.module.permission.s1.entity.IamS1Role;
import com.dataocean.module.permission.s1.entity.vo.IamS1CapabilitySnapshotVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceCapabilityVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1SubjectOptionVO;
import com.dataocean.module.permission.s1.mapper.IamS1CapabilityMapper;
import com.dataocean.module.permission.s1.mapper.IamS1PermissionRevisionMapper;
import com.dataocean.module.permission.s1.mapper.IamS1RoleMapper;
import com.dataocean.module.permission.s1.mapper.IamS1SubjectQueryMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.dataocean.module.permission.s1.support.IamS1Labels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** IAM-SIMPLE-1 能力摘要、中文模板与表单选择对象实现。 */
@Service
@RequiredArgsConstructor
public class IamS1CapabilityServiceImpl implements IamS1CapabilityService {

    private static final int USER_OPTION_LIMIT = 50;

    private final IamS1AuthorizationResolver authorizationResolver;
    private final IamS1CapabilityMapper capabilityMapper;
    private final IamS1RoleMapper roleMapper;
    private final IamS1SubjectQueryMapper subjectQueryMapper;
    private final IamS1PermissionRevisionMapper permissionRevisionMapper;
    private final IamS1AdminGuard adminGuard;

    @Override
    public IamS1CapabilitySnapshotVO snapshot(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法读取 IAM-SIMPLE-1 能力摘要");
        }
        boolean systemAdmin = authorizationResolver.isSystemAdmin(userId);
        List<String> globalFunctions = systemAdmin
                ? allFunctionCodes()
                : capabilityMapper.selectGrantedFunctionCodes(userId);
        List<IamS1DatasourceCapabilityVO> datasourceCapabilities = new ArrayList<>();
        for (IamS1DatasourceRefVO datasource : responsibleDatasources(userId)) {
            List<String> codes = systemAdmin
                    ? allFunctionCodes()
                    : capabilityMapper.selectDatasourceFunctionCodes(userId, datasource.id());
            datasourceCapabilities.add(new IamS1DatasourceCapabilityVO(datasource.id(), datasource.name(),
                    codes, functionNames(codes)));
        }
        return new IamS1CapabilitySnapshotVO(
                IamS1Constants.PROTOCOL_VERSION,
                userId,
                systemAdmin,
                globalFunctions,
                datasourceCapabilities,
                globalFunctions.contains("query:use"),
                globalFunctions.contains("query:sql:view"),
                globalFunctions.contains("query:export"),
                latestRevision());
    }

    @Override
    public List<IamS1RoleTemplateVO> roleTemplates() {
        List<IamS1RoleTemplateVO> templates = new ArrayList<>();
        templates.add(template("QUERY_USER", "普通问数用户", "只看数、不导出；访问数据由部门默认或明确授权决定。",
                List.of("query:use"), "可以问数；导出与 SQL 查看不可用。", "部门默认或明确授权", false));
        templates.add(template("BUSINESS_ANALYST", "业务分析人员", "需要自己看 SQL、导出结果做分析。",
                List.of("query:use", "query:sql:view", "query:export"), "可以问数、查看 SQL、导出结果。",
                "部门、角色或个人授权", false));
        templates.add(template("DATA_GOVERNANCE", "数据治理人员", "负责资产结构、质量检查、问题处理与规则维护。",
                List.of("metadata:view", "governance:view", "governance:check", "governance:issue:view",
                        "governance:issue:manage", "governance:rule:view", "governance:rule:manage",
                        "governance:field:view", "governance:field:manage"),
                "可以查看资产结构、执行质量检查、处理问题、维护规则与字段治理。",
                "系统管理员选择其负责源；问数另行授权", false));
        templates.add(template("SEMANTIC_MAINTAINER", "语义维护人员", "负责业务术语与知识文档的维护，不自动审核或发布。",
                List.of("metadata:view", "glossary:view", "glossary:manage", "knowledge:view", "knowledge:manage"),
                "可以查看资产结构、维护业务术语与知识文档；审核与发布不可用。",
                "系统管理员选择其负责源", false));
        templates.add(template("REVIEW_PUBLISHER", "审核发布人员", "负责快照、术语与知识的审核和发布，不自动维护内容。",
                List.of("metadata:view", "metadata:release:view", "metadata:release:review",
                        "metadata:release:publish", "glossary:view", "glossary:approve",
                        "knowledge:view", "knowledge:approve", "knowledge:publish"),
                "可以查看资产结构、审核/发布快照、审核术语与知识、发布知识。",
                "系统管理员选择其负责源", false));
        return templates;
    }

    @Override
    public List<IamS1GrantTemplateVO> grantTemplates() {
        return List.of(
                new IamS1GrantTemplateVO("DEPARTMENT_SELF", "让本部门成员查询该表",
                        "只对所选部门当前启用成员生效，不包含下级部门。", IamS1Constants.SUBJECT_DEPARTMENT,
                        IamS1Constants.DEPARTMENT_SCOPE_SELF, null),
                new IamS1GrantTemplateVO("DEPARTMENT_TREE", "让本部门及下级部门查询该表",
                        "对所选部门及其当前启用后代部门成员生效；禁用路径不继续继承。", IamS1Constants.SUBJECT_DEPARTMENT,
                        IamS1Constants.DEPARTMENT_SCOPE_INCLUDE_DESCENDANTS, null),
                new IamS1GrantTemplateVO("ROLE_LONG_TERM", "让某个角色的成员长期查询该表",
                        "对该角色当前启用的用户生效，长期有效。", IamS1Constants.SUBJECT_ROLE, null, null),
                new IamS1GrantTemplateVO("USER_TEMPORARY_30", "给某个人临时 30 天的查询权",
                        "只对该用户生效，30 天后自动失效；用于临时分析或跨部门协作。", IamS1Constants.SUBJECT_USER,
                        null, 30));
    }

    @Override
    public List<IamS1DatasourceRefVO> responsibleDatasources(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录，无法读取 IAM-SIMPLE-1 后台负责范围");
        }
        if (authorizationResolver.isSystemAdmin(userId)) {
            return allEnabledDatasources();
        }
        List<IamS1DatasourceRefVO> result = new ArrayList<>();
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (IamS1ResponsibleDatasourceFact fact : capabilityMapper.selectResponsibleDatasources(userId)) {
            if (fact.getDatasourceId() == null || !seen.add(fact.getDatasourceId())) {
                continue;
            }
            result.add(new IamS1DatasourceRefVO(fact.getDatasourceId(), fact.getDatasourceName(),
                    Integer.valueOf(IamS1Constants.ENABLED).equals(fact.getDatasourceStatus())));
        }
        return result;
    }

    @Override
    public List<IamS1DatasourceRefVO> selectableDatasources(Long userId) {
        return responsibleDatasources(userId);
    }

    @Override
    public List<IamS1SubjectOptionVO> subjectOptions(Long operatorUserId, String subjectType, String keyword) {
        adminGuard.requireGlobalFunction(operatorUserId, "security:permission:view");
        String normalized = subjectType == null ? "" : subjectType.trim().toUpperCase();
        String search = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (normalized.isBlank() || IamS1Constants.SUBJECT_USER.equals(normalized)) {
            List<IamS1SubjectOptionVO> users = selectUsers(search);
            if (!normalized.isBlank()) {
                return users;
            }
            List<IamS1SubjectOptionVO> all = new ArrayList<>(users);
            all.addAll(selectRoles());
            all.addAll(selectDepartments());
            return all;
        }
        if (IamS1Constants.SUBJECT_ROLE.equals(normalized)) {
            return selectRoles();
        }
        if (IamS1Constants.SUBJECT_DEPARTMENT.equals(normalized)) {
            return selectDepartments();
        }
        throw new BusinessException("授权主体只支持用户、角色或部门");
    }

    private List<IamS1SubjectOptionVO> selectUsers(String keyword) {
        List<IamS1SubjectOptionVO> options = new ArrayList<>();
        for (Map<String, Object> row : subjectQueryMapper.searchEnabledUsers(keyword, USER_OPTION_LIMIT)) {
            Long id = asLong(row.get("id"));
            String realName = asString(row.get("real_name"));
            String username = asString(row.get("username"));
            String name = realName == null || realName.isBlank() ? username : realName;
            options.add(new IamS1SubjectOptionVO(id, name, IamS1Constants.SUBJECT_USER,
                    IamS1Labels.subjectTypeName(IamS1Constants.SUBJECT_USER)));
        }
        return options;
    }

    private List<IamS1SubjectOptionVO> selectRoles() {
        List<IamS1SubjectOptionVO> options = new ArrayList<>();
        for (IamS1Role role : roleMapper.selectList(new LambdaQueryWrapper<IamS1Role>()
                .eq(IamS1Role::getStatus, IamS1Constants.ENABLED)
                .orderByAsc(IamS1Role::getId))) {
            if (role.getRoleCode() != null && role.getRoleCode().equals(IamS1Constants.SYSTEM_ADMIN_ROLE_CODE)) {
                // 系统管理员是服务端固定识别的主体，不作为可自由配置的授权对象出现。
                continue;
            }
            options.add(new IamS1SubjectOptionVO(role.getId(), role.getRoleName(),
                    IamS1Constants.SUBJECT_ROLE, IamS1Labels.subjectTypeName(IamS1Constants.SUBJECT_ROLE)));
        }
        return options;
    }

    private List<IamS1SubjectOptionVO> selectDepartments() {
        List<IamS1SubjectOptionVO> options = new ArrayList<>();
        for (Map<String, Object> row : subjectQueryMapper.selectEnabledDepartments()) {
            options.add(new IamS1SubjectOptionVO(asLong(row.get("id")), asString(row.get("dept_name")),
                    IamS1Constants.SUBJECT_DEPARTMENT,
                    IamS1Labels.subjectTypeName(IamS1Constants.SUBJECT_DEPARTMENT)));
        }
        return options;
    }

    private List<IamS1DatasourceRefVO> allEnabledDatasources() {
        List<IamS1DatasourceRefVO> result = new ArrayList<>();
        for (Map<String, Object> row : capabilityMapper.selectAllEnabledDatasources()) {
            result.add(new IamS1DatasourceRefVO(asLong(row.get("id")), asString(row.get("name")), true));
        }
        return result;
    }

    private IamS1RoleTemplateVO template(String code, String name, String description,
                                         List<String> functionCodes, String capabilitySummary,
                                         String dataHint, boolean systemAdminOnly) {
        Map<String, String> missing = new LinkedHashMap<>();
        List<String> names = functionNames(functionCodes);
        for (String functionCode : functionCodes) {
            IamS1FunctionCatalog.Definition definition = IamS1FunctionCatalog.find(functionCode);
            if (definition == null) {
                missing.put(functionCode, functionCode);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException("角色模板引用了未知功能码：" + String.join("、", missing.keySet()));
        }
        return new IamS1RoleTemplateVO(code, name, description, functionCodes, names,
                capabilitySummary, dataHint, systemAdminOnly);
    }

    private List<String> functionNames(List<String> functionCodes) {
        List<String> names = new ArrayList<>();
        for (String code : functionCodes) {
            IamS1FunctionCatalog.Definition definition = IamS1FunctionCatalog.find(code);
            if (definition != null) {
                names.add(definition.name());
            }
        }
        return names;
    }

    private List<String> allFunctionCodes() {
        List<String> codes = new ArrayList<>();
        for (IamS1FunctionCatalog.Definition definition : IamS1FunctionCatalog.definitions()) {
            codes.add(definition.code());
        }
        return codes;
    }

    private Long latestRevision() {
        Long revision = permissionRevisionMapper.selectCurrentRevision();
        return revision == null ? 0L : revision;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
