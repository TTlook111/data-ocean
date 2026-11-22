package com.dataocean.module.query.service;

import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.query.service.QueryProtocol.Field;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 执行前独立核对真实投影及输出规则，旧规则检查不会用于新的交付。
 */
final class QueryOutput {

    /** 首条链路可以无歧义编译为业务语义的输出限制。 */
    private static final String PROCUREMENT = "不得返回商品采购成本或采购单价";
    /** 实际列名与中文治理标签共同用于拒绝受限血缘。 */
    private static final Pattern PURCHASE_VALUE = Pattern.compile("采购.{0,8}(成本|单价|价格|进价|金额)|进货.{0,8}(成本|单价|价格|金额)|进价|"
            + "(purchase|procurement|supplier|unit|product)[_ .-]*(cost|price)|(^|[^a-z])cost([^a-z]|$)");

    /** 明确要求返回的采购金额在读取发布正文之前就拒绝。 */
    void checkDirectRequest(List<OutputRuleVO> rules, String question) {
        checkSupported(rules);
        if (!rules.isEmpty()
                && protectedValue(question)
                && !question.matches("(?s).*(?:(?:作为|用于|按|以).{0,20}(?:条件|筛选|过滤)|不(?:返回|展示|显示)).*")) {
            // 过滤类提问还需通过独立语义检查和实际投影核对。
            throw QueryFailure.denied();
        }
    }

    /** 在问数的安全边界只处理明确可核验的规则，规则未知时关闭交付。 */
    void checkSupported(List<OutputRuleVO> rules) {
        if (rules.stream().anyMatch(rule -> !PROCUREMENT.equals(normal(rule.getContent())))) {
            throw QueryFailure.denied();
        }
    }

    /** 比对返回列血缘，过滤条件的字段独立处理。 */
    void checkProjection(List<OutputRuleVO> rules, QueryCorpus.Basis basis, List<Field> fields) {
        checkSupported(rules);
        if (!rules.isEmpty()
                && fields.stream()
                        .anyMatch(field -> protectedValue(field.table() + "." + field.column() + " "
                                + basis.labels().get(field)))) {
            throw QueryFailure.denied();
        }
    }

    /** 指标说明与发布标题也属于交付内容，按同一限制核对。 */
    void checkDisclosure(List<OutputRuleVO> rules, String... texts) {
        if (!rules.isEmpty() && java.util.Arrays.stream(texts).anyMatch(this::protectedValue)) {
            throw QueryFailure.denied();
        }
    }

    /** 新旧规则比较包括全部实际修订，避免只比对规则名。 */
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
            throw new IllegalStateException("缺少标准规则摘要算法");
        }
    }

    /** 为安全投影匹配清理大小写和表示空白。 */
    private boolean protectedValue(String value) {
        return PURCHASE_VALUE.matcher(value.toLowerCase(Locale.ROOT)).find();
    }

    /** 规则书写中的末尾句号和空格不改变首条链路支持的意义。 */
    private String normal(String value) {
        return value.replaceAll("[\\s。．.]", "");
    }
}
