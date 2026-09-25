package com.dataocean.module.permission.s1.coverage;

import java.util.Map;

/**
 * S1 接口覆盖扫描的**逐端点**临时例外清单。
 *
 * <p>键是 `HTTP 方法 + 完整路径`，值是 `Controller#Handler方法|原因`。清单由覆盖扫描从真实
 * {@code HandlerMethod} 生成并冻结为安全基线：每迁移一个端点就删除一条，不新增条目。
 * 新增 Handler 若未声明 S1 注解且不在清单里，{@code IamS1EndpointCoverageTest} 会失败。</p>
 *
 * <p>禁止把它改成路径前缀匹配或 Controller 级豁免——那会让该 Controller 新增的未注解方法自动通过。</p>
 */
final class IamS1EndpointExemptions {

    static final Map<String, String> EXEMPTIONS = Map.ofEntries(
            Map.entry("DELETE /api/iam-s1/data-grants/{grantId}", "IamS1DataGrantController#revoke|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("DELETE /api/iam-s1/field-protections/{protectionId}", "IamS1FieldProtectionController#revoke|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("DELETE /api/iam-s1/roles/{roleId}", "IamS1RoleController#delete|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("DELETE /api/iam-s1/user-roles/{userRoleId}/datasources/{datasourceId}", "IamS1UserRoleController#removeDatasource|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("DELETE /api/iam-s1/users/{userId}/roles/{roleId}", "IamS1UserRoleController#remove|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/access-requests/mine", "IamS1AccessRequestController#mine|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/access-requests/queue", "IamS1AccessRequestController#queue|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/capabilities", "IamS1CapabilityController#capabilities|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/data-grants/{grantId}", "IamS1DataGrantController#get|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/data-grants", "IamS1DataGrantController#list|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/datasources/{datasourceId}/snapshots/{snapshotId}/tables/{tableName}/columns", "IamS1ResourceOptionController#columns|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/datasources/{datasourceId}/snapshots/{snapshotId}/tables", "IamS1ResourceOptionController#tables|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/datasources/{datasourceId}/snapshots", "IamS1ResourceOptionController#snapshots|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/datasources", "IamS1CapabilityController#datasources|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/field-protections", "IamS1FieldProtectionController#list|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/functions", "IamS1FunctionCatalogController#list|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/query-resources/datasources/{datasourceId}/snapshots/{snapshotId}/tables/{tableName}/columns", "IamS1UserResourceController#columns|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/query-resources/datasources/{datasourceId}/snapshots/{snapshotId}/tables", "IamS1UserResourceController#tables|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/query-resources/datasources/{datasourceId}/snapshots", "IamS1UserResourceController#snapshots|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/query-resources/datasources", "IamS1UserResourceController#datasources|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/query-resources/datasources/{datasourceId}/readiness", "IamS1UserResourceController#readiness|B6-2 正式 S1 readiness"),
            Map.entry("GET /api/iam-s1/query/history", "IamS1QueryController#history|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("GET /api/iam-s1/query/conversations", "IamS1QueryController#conversations|B6-2 正式 S1 会话链"),
            Map.entry("GET /api/iam-s1/query/conversations/{conversationId}/messages", "IamS1QueryController#conversationMessages|B6-2 正式 S1 会话链"),
            Map.entry("GET /api/iam-s1/query/tasks/{taskId}/export.csv", "IamS1QueryController#exportCsv|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("GET /api/iam-s1/query/tasks/{taskId}/export", "IamS1QueryController#export|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("GET /api/iam-s1/query/tasks/{taskId}/sql", "IamS1QueryController#viewSql|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("GET /api/iam-s1/query/tasks/{taskId}/stream", "IamS1QuerySseController#stream|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("GET /api/iam-s1/query/tasks/{taskId}", "IamS1QueryController#get|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("GET /api/iam-s1/roles/{roleId}/members", "IamS1RoleController#members|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/roles/{roleId}", "IamS1RoleController#get|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/roles", "IamS1RoleController#list|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/subjects", "IamS1CapabilityController#subjects|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/templates/grant-templates", "IamS1CapabilityController#grantTemplates|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/templates/role-templates", "IamS1CapabilityController#roleTemplates|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/user-roles/{userRoleId}/datasources", "IamS1UserRoleController#datasourcesOfBinding|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("GET /api/iam-s1/users/{userId}/roles", "IamS1UserRoleController#listUserRoles|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("PATCH /api/iam-s1/users/{userId}/roles/{roleId}/disable", "IamS1UserRoleController#disable|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/access-requests/{requestId}/review", "IamS1AccessRequestController#review|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/access-requests/{requestId}/withdraw", "IamS1AccessRequestController#withdraw|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/access-requests", "IamS1AccessRequestController#submit|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/data-grants/batch", "IamS1DataGrantController#createBatch|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/data-grants", "IamS1DataGrantController#create|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/effective-permissions/preview", "IamS1EffectivePermissionController#preview|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/field-protections", "IamS1FieldProtectionController#save|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/query/ask", "IamS1QueryController#ask|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("POST /api/iam-s1/query/tasks/{taskId}/cancel", "IamS1QueryController#cancel|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("POST /api/iam-s1/query/tasks/{taskId}/feedback", "IamS1QueryController#feedback|B2/B3 专用安全链，不接入通用切面"),
            Map.entry("DELETE /api/iam-s1/query/conversations/{conversationId}", "IamS1QueryController#archiveConversation|B6-2 正式 S1 会话链"),
            Map.entry("POST /api/iam-s1/roles", "IamS1RoleController#create|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("POST /api/iam-s1/users/{userId}/roles", "IamS1UserRoleController#assign|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("PUT /api/iam-s1/data-grants/{grantId}", "IamS1DataGrantController#update|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("PUT /api/iam-s1/roles/{roleId}", "IamS1RoleController#update|权限与组织域 B4 已完成，仍是显式 Guard"),
            Map.entry("PUT /api/iam-s1/user-roles/{userRoleId}/datasources", "IamS1UserRoleController#bindDatasources|权限与组织域 B4 已完成，仍是显式 Guard")
    );

    private IamS1EndpointExemptions() {
    }
}
