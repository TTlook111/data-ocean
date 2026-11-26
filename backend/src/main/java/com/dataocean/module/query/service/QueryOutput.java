package com.dataocean.module.query.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.service.QueryProtocol.Field;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * 执行前独立核对真实投影及输出规则，旧规则检查不会用于新的交付。
 */
final class QueryOutput {

    /**
     * 首条链路可以无歧义编译为业务语义的输出限制。
     */
    private static final String PROCUREMENT = "不得返回商品采购成本或采购单价";
    /**
     * 实际列名与中文治理标签共同用于拒绝受限血缘。
     */
    private static final Pattern PURCHASE_VALUE = Pattern.compile("采购.{0,8}(成本|单价|价格|进价|金额)|进货.{0,8}(成本|单价|价格|金额)|进价|"
            + "(purchase|procurement|supplier|unit|product)[_ .-]*(cost|price)|(^|[^a-z])cost([^a-z]|$)");

    /**
     * 明确表示采购价格的字段语义，供 JSON 键和文本标签共用。
     */
    private static final Pattern PROCUREMENT_LABEL = Pattern.compile(
            "(?:(?:商品)?(?:采购(?:成本|单价|价格|进价|金额)|进货(?:成本|单价|价格|金额)|进价)"
                    + "|\\b(?:purchase|procurement|supplier)[ _.-]*(?:unit[ _.-]*)?(?:cost|price)\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 完整读取金额的数字部分，避免把年份前几位或日期片段当成金额。
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:(?:人民币|美元|CNY|RMB|USD|[¥$€£])\\s*)?[+-]?\\s*"
                    + "(?:(?>(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)(?!\\s*(?:[年月日]|[-/.]\\s*\\d))"
                    + "|[零〇一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟点]+\\s*(?:元|圆|块|美元|角|分))",
            Pattern.CASE_INSENSITIVE);

    /**
     * 文本须把采购标签和金额关联起来，名称及说明文字保留原意。
     */
    private static final Pattern PROCUREMENT_AMOUNT = Pattern.compile(
            PROCUREMENT_LABEL.pattern() + "\\s*(?:\\([^()\\r\\n]{1,12}\\)\\s*)?(?:约为|为|是|is\\b)?\\s*[:=]?\\s*"
                    + AMOUNT.pattern(),
            Pattern.CASE_INSENSITIVE);

    /**
     * 只给明确的日期元数据字段放行匹配其语义的标量。
     */
    private static final Pattern YEAR_FIELD =
            Pattern.compile("(?i)(?:(?:business|effective|valid|fiscal)[ _-]*)?year|(?:业务|生效|会计)?(?:年份|年度|年)");

    /**
     * 业务日期可按标准 ISO 文本或八位整型保存。
     */
    private static final Pattern DATE_FIELD =
            Pattern.compile("(?i)(?:(?:business|effective|start|end)[ _-]*)?date|(?:业务|生效|开始|结束)?日期");

    /**
     * 明确要求返回的采购金额在读取发布正文之前就拒绝。
     */
    void checkDirectRequest(List<OutputRuleVO> rules, String question) {
        checkSupported(rules);
        if (!rules.isEmpty()
                && protectedValue(question)
                && !question.matches("(?s).*(?:(?:作为|用于|按|以).{0,20}(?:条件|筛选|过滤)|不(?:返回|展示|显示)).*")) {
            // 过滤类提问还需通过独立语义检查和实际投影核对。
            throw QueryFailure.denied();
        }
    }

    /**
     * 在问数的安全边界只处理明确可核验的规则，规则未知时关闭交付。
     */
    void checkSupported(List<OutputRuleVO> rules) {
        if (rules.stream().anyMatch(rule -> !PROCUREMENT.equals(normal(rule.getContent())))) {
            // 对当前无法核查的规则关闭结果交付。
            throw QueryFailure.denied();
        }
    }

    /**
     * 比对返回列血缘，过滤条件的字段独立处理。
     */
    void checkProjection(List<OutputRuleVO> rules, QueryCorpus.Basis basis, List<Field> fields) {
        checkSupported(rules);
        if (!rules.isEmpty()
                && fields.stream()
                        .anyMatch(field -> protectedValue(field.table() + "." + field.column() + " "
                                + basis.labels().get(field)))) {
            // 真实投影的任一字段受限时不执行。
            throw QueryFailure.denied();
        }
    }

    /**
     * 指标说明与发布标题也属于交付内容，按同一限制核对。
     */
    void checkDisclosure(List<OutputRuleVO> rules, String... texts) {
        if (!rules.isEmpty() && Arrays.stream(texts).anyMatch(this::protectedValue)) {
            // 查询说明和标题也属于返回给用户的内容。
            throw QueryFailure.denied();
        }
    }

    /**
     * 使用同一套本地校验保护新结果、历史快照与说明外发。
     */
    void checkResult(List<OutputRuleVO> rules, QueryDataVO data, JsonCodec json) {
        if (rules.isEmpty() || data.getRows() == null) {
            // 没有适用限制或没有单元格时，无需检查文本内容。
            return;
        }
        if (data.getRows().stream()
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .anyMatch(value -> restrictedCell(value, json))) {
            // 整份结果不交付，避免删行或删值改变原查询含义。
            throw BizIllegalException.denied();
        }
    }

    /**
     * 文本关联金额和 JSON 字段金额都按单元格校验，原值只读。
     */
    private boolean restrictedCell(String value, JsonCodec json) {
        if (PROCUREMENT_AMOUNT
                .matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .find()) {
            // 普通文本和包含明确金额句子的 JSON 字符串都受限制。
            return true;
        }
        var content = value.stripLeading();
        if (content.startsWith("{") || content.startsWith("[")) {
            // 源库 JSON 与普通文本都以 JDBC 字符串交付，键值关系通过解析取得。
            try {
                return restrictedJson(json.mapper().readTree(content), false);
            } catch (JacksonException invalidJson) {
                // 以括号开头的普通文本沿用前面的文本校验结果。
                return false;
            }
        }
        return false;
    }

    /**
     * 对象键承载采购口径，数组继承上一级口径，普通文本值仍使用金额校验。
     */
    private boolean restrictedJson(JsonNode node, boolean procurement) {
        if (node.isObject()) {
            // 能识别采购含义的键继续约束其下方的值。
            return node.properties().stream().anyMatch(entry -> {
                // 日期元数据必须是确切的标量值，嵌套对象仍需要继续扫描。
                var label = Normalizer.normalize(entry.getKey(), Normalizer.Form.NFKC)
                        .strip();
                var value = entry.getValue();
                return !(procurement && temporalMetadata(label, value))
                        && restrictedJson(
                                value,
                                procurement || PROCUREMENT_LABEL.matcher(label).matches());
            });
        }
        if (node.isArray()) {
            // 数组内的每项保留该数组所在字段的含义。
            for (JsonNode item : node) {
                if (restrictedJson(item, procurement)) {
                    // 任一项确认受限即停止。
                    return true;
                }
            }
            return false;
        }
        if (node.isString()) {
            // 已识别的采购字段也可能把数值存为字符串。
            var value =
                    Normalizer.normalize(node.asString(), Normalizer.Form.NFKC).strip();
            return PROCUREMENT_AMOUNT.matcher(value).find()
                    || procurement && AMOUNT.matcher(value).lookingAt();
        }
        return procurement && node.isNumber();
    }

    /**
     * 按键和值同时核实年份或日期，含金额文本及复合节点继续走原校验。
     */
    private boolean temporalMetadata(String label, JsonNode node) {
        if (!node.isIntegralNumber() && !node.isString()) {
            // 复合节点可能同时含日期和金额，不能整段豁免。
            return false;
        }
        var value = node.isString()
                ? Normalizer.normalize(node.asString(), Normalizer.Form.NFKC).strip()
                : node.asString();
        if (YEAR_FIELD.matcher(label).matches()) {
            // 年份使用四位自然年，币种、单位及金额小数不属于年份。
            return value.matches("[1-9]\\d{3}");
        }
        if (!DATE_FIELD.matcher(label).matches() || !value.matches("\\d{4}-\\d{2}-\\d{2}|\\d{8}")) {
            // 其他标签和数值格式继续沿用采购语义。
            return false;
        }
        try {
            // 验证日历日期，避免把任意八位金额当成日期。
            var format = value.matches("\\d{8}") ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE;
            LocalDate.parse(value, format);
            return true;
        } catch (DateTimeParseException invalid) {
            // 格式与日期都不符时继续走采购值检查。
            return false;
        }
    }

    /**
     * 新旧规则比较包括全部实际修订，避免只比对规则名。
     */
    String signature(List<OutputRuleVO> rules) {
        var revisions = rules.stream()
                .map(rule -> rule.getId() + "/" + rule.getVersion() + "/" + rule.getContent())
                .sorted()
                .toList();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(String.join("\u0000", revisions).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // 此环境缺少固定摘要算法，无法继续使用规则签名。
            throw new IllegalStateException("缺少标准规则摘要算法", impossible);
        }
    }

    /**
     * 为安全投影匹配清理大小写和表示空白。
     */
    private boolean protectedValue(String value) {
        return PURCHASE_VALUE.matcher(value.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * 规则书写中的末尾句号和空格不改变首条链路支持的意义。
     */
    private String normal(String value) {
        return value.replaceAll("[\\s。．.]", "");
    }
}
