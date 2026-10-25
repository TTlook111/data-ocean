package com.dataocean.module.prompt.service;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.prompt.entity.vo.PromptSceneVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.List;
import java.util.regex.Pattern;

/** 场景合同由代码维护；后台策略只允许简单变量，不执行模板表达式。 */
public final class PromptSceneRegistry {
    private PromptSceneRegistry() { }
    private static final String FIXED = "上下文由 Java 按权限与治理过滤；输出仍经解析、AST、预算或人工审核门禁。策略不能改变这些程序约束。";
    private static PromptSceneVO scene(String code, String name, String group, String consumer, List<String> variables, String output, String metric) {
        java.util.Map<String, String> descriptions = variables.stream().collect(java.util.stream.Collectors.toMap(v -> v, v -> switch (v) {
            case "input" -> switch (code) {
                case "schema_linking" -> "用户问题、最近对话、摘要、授权候选目录与已审核事实的 JSON";
                case "sql_semantic_check" -> "问题意图、候选 SQL、AST 使用范围、已审核事实与预检结果的 JSON";
                case "result_verification" -> "问题意图、列、Java 最终保护后的前 20 行样本及行数的 JSON";
                case "field_semantic" -> "Java 已过滤的当前字段、同表字段、合规关系和审核术语；不含业务行采样";
                case "semantic_supplement" -> "本批已授权表字段依赖、元数据与已审核术语的 JSON";
                default -> "Java 已筛选的知识类型、原始资料和已审核字段依据的 JSON";
            };
            case "question" -> "用户问题；SQL 场景可包含规划改写与修复反馈";
            case "schema" -> "通过当前权限与治理过滤的表字段结构";
            case "rag" -> "当前授权且版本匹配的检索事实";
            case "glossary" -> "已审核且依赖完整的业务术语";
            case "few_shot" -> "合规的问答示例";
            case "history" -> "最近对话";
            case "summary", "previous_summary" -> "已有长期会话摘要";
            case "messages" -> "需要合并到摘要的新消息增量";
            case "current_date" -> "本轮日期";
            case "columns" -> "图表输入的结果列信息";
            case "data" -> "图表输入的有限数据预览";
            default -> v;
        }));
        return new PromptSceneVO(code, name, group, consumer, "1", variables, descriptions, output, FIXED, metric);
    }
    public static final List<PromptSceneVO> SCENES = List.of(
        scene("schema_linking", "问题理解与规划", "问数", "iam_s1/graph.py · schema_linking", List.of("input"), "JSON: rewrittenQuestion, intent, selectedResources, clarificationNeeded, clarification", "结构校验与业务澄清需结合任务结果评估"),
        scene("sql_generation", "SQL 生成", "问数", "iam_s1/graph.py · sql_generation", List.of("question", "schema", "rag", "glossary", "few_shot", "history", "summary"), "单条 SELECT；AST 和输出来源门禁独立执行", "任务执行结果不等于 SQL 业务正确率"),
        scene("sql_semantic_check", "SQL 语义核对", "问数", "iam_s1/graph.py · sql_semantic_check", List.of("input"), "JSON: decision(MATCH/RETRY/RETRIEVE/CLARIFY/UNSAFE), reason", "核对结论需结合任务及人工验收"),
        scene("result_verification", "结果核验", "问数", "iam_s1/graph.py · result_verification", List.of("input"), "JSON: decision(ANSWER/RETRY/CLARIFY), answer, reason", "生成回答不等于业务验收通过"),
        scene("chart_generation", "图表生成", "问数", "chart/service.py", List.of("question", "columns", "data"), "ECharts option JSON 或 null；固定校验器验证", "模型返回与图表结构校验分开"),
        scene("field_semantic", "字段解释候选", "知识治理", "knowledge/semantic.py · generate_field_candidate", List.of("input"), "JSON: businessName, meaning, unit, currency, enumSemantics, metricDefinition, applicability, evidence, unresolvedItems", "人工采纳/审核率尚无自动聚合，不能用查询成功率替代"),
        scene("semantic_supplement", "语义知识补写", "知识治理", "knowledge/semantic.py · supplement", List.of("input"), "JSON: candidates[{text, dependencies}]，每批 1–4 条；依赖须来自输入", "人工采纳/审核率尚无自动聚合"),
        scene("business_knowledge", "业务知识候选", "知识治理", "knowledge/business.py", List.of("input"), "JSON: body（字符串值对象）, evidence, unresolvedItems", "人工采纳/审核率尚无自动聚合"),
        scene("conversation_summary", "会话上下文摘要", "会话", "conversation/summary.py", List.of("previous_summary", "messages", "current_date"), "结构化摘要 JSON 对象", "摘要质量需业务评估，不使用查询成功率")
    );
    public static PromptSceneVO find(String code) {
        return SCENES.stream().filter(s -> s.code().equals(code)).findFirst().orElse(null);
    }
    public static PromptSceneVO require(String code) {
        PromptSceneVO scene = find(code);
        if (scene == null) throw new BusinessException(400, "该历史模板没有当前消费者，只能查看历史");
        return scene;
    }
    public static void validate(String code, String content, ObjectMapper mapper) {
        PromptSceneVO scene = require(code);
        if (content == null || content.isBlank() || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000) throw new BusinessException(400, "策略内容为空或超过 60000 UTF-8 字节");
        String system = "";
        String user = content;
        if (content.stripLeading().startsWith("{") && !content.stripLeading().startsWith("{{")) {
            try {
                JsonNode root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
                if (!root.isObject() || root.size() != 3 || !root.path("formatVersion").isIntegralNumber()
                        || !java.math.BigInteger.ONE.equals(root.path("formatVersion").bigIntegerValue())
                        || !root.path("system").isTextual() || !root.path("user").isTextual()) throw new IllegalArgumentException();
                system = root.get("system").asText();
                user = root.get("user").asText();
                if (system.isBlank() || user.isBlank()) throw new IllegalArgumentException();
            } catch (Exception ex) { throw new BusinessException(400, "策略包必须包含 formatVersion=1、非空 system 和 user"); }
        }
        if (system.contains("{{") || system.contains("{%") || system.contains("{#")) {
            throw new BusinessException(400, "角色指令不允许包含动态业务变量；请将变量放入任务指令");
        }
        String combined = system + "\n" + user;
        var matcher = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}").matcher(combined);
        var found = new java.util.HashSet<String>();
        while (matcher.find()) {
            String variable = matcher.group(1);
            if (!scene.variables().contains(variable)) throw new BusinessException(400, "未知策略变量：" + variable);
            found.add(variable);
        }
        String remaining = combined.replaceAll("\\{\\{\\s*[A-Za-z_][A-Za-z0-9_]*\\s*}}", "");
        if (remaining.contains("{{") || remaining.contains("{%") || remaining.contains("{#")) throw new BusinessException(400, "策略只支持简单变量，不支持表达式或控制流");
        if (!found.containsAll(scene.variables())) throw new BusinessException(400, "策略必须保留全部场景变量：" + String.join(", ", scene.variables()));
    }
}
