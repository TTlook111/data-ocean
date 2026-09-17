package com.dataocean.module.permission.s1.catalog;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * IAM-SIMPLE-1 固定功能目录及唯一依赖规则。
 * <p>
 * 目录只允许由前向 migration 初始化，管理员请求不能创建任意功能码。
 * </p>
 */
public final class IamS1FunctionCatalog {

    private static final List<String> SYSTEM_ADMIN_ONLY_CONFIGURATION_CODES = List.of(
            "datasource:manage",
            "security:permission:manage",
            "security:mask:manage",
            "security:approval:review",
            "organization:user:manage",
            "organization:role:manage",
            "organization:department:manage",
            "organization:user:export",
            "audit:export",
            "system:runtime:manage",
            "system:ai-config:manage"
    );

    private static final List<Definition> DEFINITIONS = List.of(
            d("query:use", "使用问数", "提交自然语言问题并查看允许范围内的查询结果。", "问数", "问数工具"),
            d("query:sql:view", "查看 SQL", "查看当前问数任务生成的 SQL，不扩大数据范围。", "问数", "问数工具", "query:use"),
            d("query:export", "导出结果", "导出当前已获准且已再次检查保护规则的查询结果。", "问数", "问数工具", "query:use"),
            d("admin:workbench:view", "查看工作台", "查看后台工作台摘要和待处理事项。", "工作台", "工作台"),
            d("datasource:view", "查看数据源", "查看有后台负责范围的数据源基本信息和状态。", "数据接入", "数据源"),
            d("datasource:manage", "维护数据源", "创建、编辑、启停和维护数据源配置。", "数据接入", "数据源", "datasource:view"),
            d("metadata:collect:view", "查看采集任务", "查看元数据采集任务和执行状态。", "数据资产", "采集任务"),
            d("metadata:collect:run", "执行采集", "发起允许的数据源元数据采集。", "数据资产", "采集任务", "metadata:collect:view"),
            d("metadata:view", "查看资产结构", "查看已治理元数据的表、字段和资产结构。", "数据资产", "资产目录"),
            d("metadata:release:view", "查看元数据版本", "查看元数据快照版本和发布状态。", "数据治理", "版本发布"),
            d("metadata:release:review", "审核快照", "审核元数据快照是否可以进入发布流程。", "数据治理", "版本发布", "metadata:release:view"),
            d("metadata:release:publish", "发布/回滚快照", "发布或回滚已审核的元数据快照。", "数据治理", "版本发布", "metadata:release:view"),
            d("governance:view", "查看治理总览", "查看治理质量、状态和风险总览。", "数据治理", "治理总览"),
            d("governance:check", "执行质量检查", "对元数据快照执行质量检查。", "数据治理", "治理总览", "governance:view"),
            d("governance:issue:view", "查看质量问题", "查看元数据质量问题及其处理状态。", "数据治理", "问题中心"),
            d("governance:issue:manage", "处理质量问题", "分配、处理和批量更新质量问题。", "数据治理", "问题中心", "governance:issue:view"),
            d("governance:rule:view", "查看治理规则", "查看数据质量规则和启用状态。", "数据治理", "规则与状态"),
            d("governance:rule:manage", "维护治理规则", "维护治理规则及其启用状态。", "数据治理", "规则与状态", "governance:rule:view"),
            d("governance:field:view", "查看字段治理", "查看字段治理状态和可信度信息。", "数据治理", "字段治理"),
            d("governance:field:manage", "维护字段治理/审核反馈", "维护字段治理状态并处理字段反馈。", "数据治理", "字段治理", "governance:field:view"),
            d("glossary:view", "查看业务术语", "查看已登记的业务术语及其状态。", "语义中心", "业务术语"),
            d("glossary:manage", "维护业务术语", "新建、修改和关联业务术语。", "语义中心", "业务术语", "glossary:view"),
            d("glossary:approve", "审核术语", "审核业务术语及其发布前状态。", "语义中心", "业务术语", "glossary:view"),
            d("knowledge:view", "查看知识文档", "查看语义知识文档、版本和索引状态。", "语义中心", "语义知识"),
            d("knowledge:manage", "维护知识文档", "创建、编辑和提交知识文档。", "语义中心", "语义知识", "knowledge:view"),
            d("knowledge:approve", "审核知识文档", "审核知识文档版本。", "语义中心", "语义知识", "knowledge:view"),
            d("knowledge:publish", "发布/回滚知识", "发布或回滚已审核知识版本并管理索引状态。", "语义中心", "语义知识", "knowledge:view"),
            d("prompt:view", "查看 AI 提示词", "查看提示词策略摘要和版本状态。", "语义中心", "Prompt 策略"),
            d("prompt:manage", "维护 AI 提示词", "维护提示词内容并提交审核。", "语义中心", "Prompt 策略", "prompt:view"),
            d("prompt:approve", "审核 AI 提示词", "审核提示词版本。", "语义中心", "Prompt 策略", "prompt:view"),
            d("security:permission:view", "查看授权配置", "查看 S1 角色、负责源和授权配置摘要。", "权限与组织", "授权配置"),
            d("security:permission:manage", "维护授权配置", "维护允许管理范围内的授权配置。", "权限与组织", "授权配置", "security:permission:view"),
            d("security:mask:view", "查看字段保护", "查看字段保护状态和策略摘要。", "权限与组织", "字段保护"),
            d("security:mask:manage", "维护字段保护", "维护字段隐藏、保护和值展示策略。", "权限与组织", "字段保护", "security:mask:view"),
            d("security:effective:view", "查看用户实际权限", "查看用户当前实际生效的 S1 能力和范围摘要。", "权限与组织", "实际权限"),
            d("security:approval:view", "查看访问申请", "查看本人或负责范围内的访问申请。", "权限与组织", "访问审批"),
            d("security:approval:review", "审批数据访问", "审批负责范围内的数据访问申请。", "权限与组织", "访问审批", "security:approval:view"),
            d("organization:user:view", "查看用户", "查看用户基本资料、部门和角色摘要。", "权限与组织", "用户"),
            d("organization:user:manage", "维护用户", "维护普通账号资料、启停、部门和业务角色。", "权限与组织", "用户", "organization:user:view"),
            d("organization:user:export", "导出用户列表", "导出允许查看的用户基础信息。", "权限与组织", "用户", "organization:user:view"),
            d("organization:role:view", "查看角色", "查看角色用途、功能组合和成员。", "权限与组织", "角色"),
            d("organization:role:manage", "维护角色", "新建或修改非系统管理员角色及其固定功能组合。", "权限与组织", "角色", "organization:role:view"),
            d("organization:department:view", "查看部门", "查看真实组织部门树和状态。", "权限与组织", "部门"),
            d("organization:department:manage", "维护部门", "维护符合组织规则的部门结构。", "权限与组织", "部门", "organization:department:view"),
            d("organization:permission:view", "查看功能说明", "查看固定 54 项功能的中文作用和边界。", "权限与组织", "功能目录"),
            d("audit:view", "查看查询审计", "查看负责范围内的查询执行记录、状态和统计。", "运营与平台", "查询分析"),
            d("audit:export", "导出审计", "导出允许查看的安全审计信息，不含业务敏感原值。", "运营与平台", "查询分析", "audit:view"),
            d("lineage:view", "查看数据血缘", "查看表字段来源关系和影响分析。", "运营与平台", "数据血缘", "metadata:view"),
            d("lineage:manage", "维护血缘关系", "维护允许范围内的数据血缘关系。", "运营与平台", "数据血缘", "lineage:view"),
            d("system:runtime:view", "查看运行状态", "查看服务、连接池和告警配置状态。", "运营与平台", "运行监控"),
            d("system:runtime:manage", "维护运行监控", "维护告警规则或重置连接池并说明影响。", "运营与平台", "运行监控", "system:runtime:view"),
            d("operation-log:view", "查看操作日志", "查看管理员操作日志，只读不可修改。", "运营与平台", "操作日志"),
            d("system:ai-config:view", "查看 AI 配置", "查看模型和供应商摘要，不显示密钥原值。", "运营与平台", "AI 配置"),
            d("system:ai-config:manage", "维护 AI 配置", "维护供应商、模型和运行参数，不显示密钥原值。", "运营与平台", "AI 配置", "system:ai-config:view")
    );

    private IamS1FunctionCatalog() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Definition find(String code) {
        if (code == null) {
            return null;
        }
        return DEFINITIONS.stream().filter(item -> item.code().equals(code)).findFirst().orElse(null);
    }

    public static List<String> expand(Collection<String> requestedCodes) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        if (requestedCodes == null) {
            return List.of();
        }
        for (String code : requestedCodes) {
            expandOne(code, expanded, new LinkedHashSet<>());
        }
        return List.copyOf(expanded);
    }

    /** 敏感配置只能由系统管理员创建或修改。 */
    public static boolean requiresSystemAdminRoleConfiguration(String code) {
        return SYSTEM_ADMIN_ONLY_CONFIGURATION_CODES.contains(code);
    }

    /** 后台角色只能由系统管理员分配，业务问数角色可以由用户管理员分配。 */
    public static boolean requiresSystemAdminRoleBinding(String code) {
        return code != null && !code.startsWith("query:");
    }

    private static void expandOne(String code, Set<String> expanded, Set<String> visiting) {
        Definition definition = find(code);
        if (definition == null) {
            throw new IllegalArgumentException("未知 IAM-SIMPLE-1 功能码: " + code);
        }
        if (!visiting.add(code)) {
            throw new IllegalStateException("IAM-SIMPLE-1 功能依赖存在循环: " + code);
        }
        for (String dependency : definition.dependencies()) {
            expandOne(dependency, expanded, visiting);
        }
        visiting.remove(code);
        expanded.add(code);
    }

    private static Definition d(String code, String name, String description, String domain, String workspace,
                                String... dependencies) {
        return new Definition(code, name, description, domain, workspace, List.of(dependencies));
    }

    public record Definition(String code, String name, String description, String domain,
                             String workspace, List<String> dependencies) {
    }
}
