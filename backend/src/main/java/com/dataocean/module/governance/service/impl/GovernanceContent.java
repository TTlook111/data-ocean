package com.dataocean.module.governance.service.impl;

import com.dataocean.common.ErrorVO.FieldErrorVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.FieldValidationException;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.governance.entity.dto.CodeMeaningDTO;
import com.dataocean.module.governance.entity.dto.ColumnDefinitionDTO;
import com.dataocean.module.governance.entity.dto.TableDefinitionDTO;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 解析明确的表说明和字段类型，并对保存与确认采用一致的业务字段定位。
 */
@Component
@RequiredArgsConstructor
public class GovernanceContent {

    /**
     * 短文本使用有界名称、格式和编码语义。
     */
    private static final Set<String> SHORT_FIELDS = Set.of("businessName", "unit", "format", "businessTimeZone");

    /**
     * 共用的严格 JSON 映射器。
     */
    private final JsonCodec json;

    /**
     * 通过对应 DTO 拒绝未知字段并规范化内容，普通空白可先保存。
     */
    public ObjectNode normalize(boolean column, JsonNode input) {
        if (!input.isObject()) {
            // 空对象是合法空草稿，任意数组或字符串不属于业务内容。
            throw BizIllegalException.invalid("治理内容需要填写已声明字段");
        }
        try {
            // 按目标条目绑定类型，先拒绝不符合声明的字段。
            Class<?> type = column ? ColumnDefinitionDTO.class : TableDefinitionDTO.class;
            var definition = json.read(input.toString(), type);
            ObjectNode typed = json.mapper().valueToTree(definition);
            var result = json.mapper().createObjectNode();
            for (String key : typed.propertyNames()) {
                // 只处理该模型实际声明并提交的内容。
                JsonNode raw = input.path(key);
                if (raw.isMissingNode() || raw.isNull()) {
                    // 保持空白和显式清空的归一结果一致。
                    continue;
                }
                if ("valueMappings".equals(key)) {
                    // 逐项核对字符串编码，前导零不参与数值转换。
                    normalizeMappings(result, raw, ((ColumnDefinitionDTO) definition).valueMappings());
                } else {
                    // 服务端不接受把数字或布尔值隐式转为业务文本。
                    if (!raw.isString()) {
                        throw invalid(key, "请输入文本");
                    }
                    String value = raw.asString().strip();
                    if (!value.isBlank()) {
                        // 使用 Unicode 字符计数，字符边界通过接口正面反馈。
                        checkLength(key, value, SHORT_FIELDS.contains(key) ? 160 : 4000);
                        result.put(key, value);
                    }
                }
            }
            return result;
        } catch (JacksonException error) {
            // 失败不回显原有说明或其他可能受限的字段值。
            throw BizIllegalException.invalid("治理内容字段或格式不正确");
        }
    }

    /**
     * 维护任意业务路径引用之前，核对其为受支持的当前内容项。
     */
    public void requirePath(boolean column, String path) {
        Class<?> type = column ? ColumnDefinitionDTO.class : TableDefinitionDTO.class;
        boolean known = List.of(type.getRecordComponents()).stream().anyMatch(field -> field.getName().equals(path));
        if (!known) {
            // 证据及核对问题只能关联当前定义的真实字段。
            throw BizIllegalException.invalid("来源或核对项对应的业务字段不存在");
        }
    }

    /**
     * 仅阻断必要定义和实际未核清的关键问题。
     */
    public void confirm(boolean column, String nativeType, JsonNode content, String comment) {
        var errors = new ArrayList<FieldErrorVO>();
        if (column) {
            // 原生日期时间与明确业务含义决定需提供的值域说明。
            validateField(nativeType, content, comment, errors);
        } else {
            // 表说明确认必须明示业务用途与记录粒度。
            require(content, "purpose", "请填写业务用途", errors);
            require(content, "rowGrain", "请填写一行代表什么", errors);
        }
        if (!errors.isEmpty()) {
            // 界面将真实业务门槛定位到对应输入。
            throw new FieldValidationException(errors);
        }
    }

    /**
     * 核对动态列义、业务时间和适用的编码或单位。
     */
    private void validateField(String nativeType, JsonNode content, String comment, List<FieldErrorVO> errors) {
        require(content, "meaning", "请填写业务含义", errors);
        String meaning = (content.path("meaning").asText("") + " " + content.path("businessName").asText("")
                + " " + (comment == null ? "" : comment)).toLowerCase(Locale.ROOT);
        if (meaning.matches("(?s).*(金额|数量|单价|重量|库存量|\\bamount\\b|\\bquantity\\b).*")) {
            // 金额及数量的定义应说明单位，也可说明所依据的单位字段。
            require(content, "unit", "请明确金额或数量的业务单位", errors);
        }
        if (meaning.matches("(?s).*(状态|编码|\\bstatus\\b|\\benum\\b).*")
                && !content.has("valueMappings") && !known(content.path("valueDescription").asText(""))) {
            // 编码可用逐行映射或明确取值说明表达。
            errors.add(error("valueDescription", "请说明实际适用的编码和取值"));
        }
        String type = nativeType.toUpperCase(Locale.ROOT);
        if (type.contains("TIME") || type.equals("DATE")) {
            // 时刻与仅业务日期需要区分，技术写入时间不能代替业务解释。
            require(content, "timeSemantics", "请说明业务时间含义", errors);
        }
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            // 明确已知的时区，只有业务说明实际声明无时区时可省略。
            String zone = content.path("businessTimeZone").asText("");
            if (zone.isBlank() && !content.path("timeSemantics").asText("").contains("无时区")) {
                errors.add(error("businessTimeZone", "请明确业务时区或说明无时区含义"));
            } else if (!zone.isBlank()) {
                // 对可计算时区使用 JVM 的标准 IANA 或偏移校验。
                try {
                    ZoneId.of(zone);
                } catch (DateTimeException failure) {
                    errors.add(error("businessTimeZone", "请填写有效的时区，如 Asia/Shanghai"));
                }
            }
        }
    }

    /**
     * 核对编码表输入的上限、唯一性与完整性。
     */
    private void normalizeMappings(ObjectNode result, JsonNode raw, List<CodeMeaningDTO> mappings) {
        if (!raw.isArray() || mappings == null || mappings.size() > 200) {
            // 编码列表不得改变业务字段类型或超出接口限额。
            throw invalid("valueMappings", "编码说明最多填写 200 行");
        }
        if (mappings.isEmpty()) {
            // 忽略未添加任何一行的普通空白。
            return;
        }
        var values = result.putArray("valueMappings");
        var codes = new HashSet<String>();
        for (int index = 0; index < mappings.size(); index++) {
            // 字符串比较保留原编码形式，定位到首个实际错误。
            var mapping = mappings.get(index);
            String path = "valueMappings[" + index + "]";
            if (mapping == null || !raw.get(index).path("code").isString()
                    || !raw.get(index).path("meaning").isString()
                    || mapping.code().isBlank() || mapping.meaning().isBlank()
                    || !codes.add(mapping.code().strip())) {
                throw invalid(path, "请填写唯一编码及对应含义");
            }
            checkLength(path + ".code", mapping.code().strip(), 160);
            checkLength(path + ".meaning", mapping.meaning().strip(), 400);
            values.addObject().put("code", mapping.code().strip()).put("meaning", mapping.meaning().strip());
        }
    }

    /**
     * 判断业务条件中明确的未知和未填写值。
     */
    private boolean known(String value) {
        return !value.isBlank() && !Set.of("未知", "待确认", "待核实", "待补充", "不适用").contains(value);
    }

    /**
     * 将必要字段缺失纳入单次校验回执。
     */
    private void require(JsonNode content, String key, String message, List<FieldErrorVO> errors) {
        if (!known(content.path(key).asText(""))) {
            // 缺字段不允许提交确认为业务事实。
            errors.add(error(key, message));
        }
    }

    /**
     * 使用通用字段错误信封展示定位。
     */
    private FieldErrorVO error(String path, String message) {
        return new FieldErrorVO("draft.content." + path, "VALIDATION_FAILED", message);
    }

    /**
     * 给单项输入规范错误保留业务字段路径。
     */
    private FieldValidationException invalid(String path, String message) {
        return new FieldValidationException(List.of(error(path, message)));
    }

    /**
     * 规范化后按真实 Unicode 字符校验限额。
     */
    private void checkLength(String key, String value, int maximum) {
        if (value.codePointCount(0, value.length()) > maximum) {
            // 保留输入并反馈真实字段容量。
            throw invalid(key, "内容不能超过 " + maximum + " 字");
        }
    }
}
