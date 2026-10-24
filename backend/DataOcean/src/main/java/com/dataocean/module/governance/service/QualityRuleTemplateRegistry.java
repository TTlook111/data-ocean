package com.dataocean.module.governance.service;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.governance.entity.po.MetadataQualityRule;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只注册存在真实 QualityChecker 处理器的规则模板；不接受 SQL、脚本或自由表达式。 */
@Component
public class QualityRuleTemplateRegistry {
    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_DATASOURCE = "DATASOURCE";
    public static final String SCOPE_TABLE = "TABLE";
    public static final String SCOPE_COLUMN = "COLUMN";

    @Getter
    public static final class ParameterDefinition {
        private final String key;
        private final String label;
        private final String type;
        private final BigDecimal minimum;
        private final BigDecimal maximum;
        private final Object defaultValue;

        public ParameterDefinition(String key, String label, String type, String minimum,
                                   String maximum, Object defaultValue) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.minimum = minimum == null ? null : new BigDecimal(minimum);
            this.maximum = maximum == null ? null : new BigDecimal(maximum);
            this.defaultValue = defaultValue;
        }
    }

    @Getter
    public static final class Definition {
        private final String code;
        private final String name;
        private final String dimension;
        private final String targetType;
        private final String checkType;
        private final String description;
        private final String handlerDimension;
        private final Set<String> allowedScopes;
        private final List<ParameterDefinition> parameters;

        private Definition(String code, String name, String dimension, String targetType,
                           String checkType, String description, String handlerDimension,
                           Set<String> allowedScopes, List<ParameterDefinition> parameters) {
            this.code = code;
            this.name = name;
            this.dimension = dimension;
            this.targetType = targetType;
            this.checkType = checkType;
            this.description = description;
            this.handlerDimension = handlerDimension;
            this.allowedScopes = allowedScopes;
            this.parameters = parameters;
        }
    }

    private static final Set<String> GLOBAL_SOURCE = Set.of(SCOPE_GLOBAL, SCOPE_DATASOURCE);
    private static final Set<String> TABLE_SCOPES = Set.of(SCOPE_GLOBAL, SCOPE_DATASOURCE, SCOPE_TABLE);
    private static final Set<String> COLUMN_SCOPES = Set.of(SCOPE_GLOBAL, SCOPE_DATASOURCE, SCOPE_TABLE, SCOPE_COLUMN);
    private final Map<String, Definition> definitions;

    public QualityRuleTemplateRegistry() {
        Map<String, Definition> values = new LinkedHashMap<>();
        add(values, schema("COMP_TABLE_COMMENT_MISSING", "表注释缺失", MetadataQualityRule.DIM_COMPLETENESS,
                MetadataQualityRule.TARGET_TABLE, "检查表是否包含业务说明。", TABLE_SCOPES));
        add(values, schema("COMP_COLUMN_COMMENT_MISSING", "字段注释缺失", MetadataQualityRule.DIM_COMPLETENESS,
                MetadataQualityRule.TARGET_COLUMN, "检查字段是否包含业务说明。", COLUMN_SCOPES));
        add(values, schema("COMP_PRIMARY_KEY_MISSING", "主键缺失", MetadataQualityRule.DIM_COMPLETENESS,
                MetadataQualityRule.TARGET_TABLE, "检查表是否定义主键。", TABLE_SCOPES));
        add(values, schema("ACCU_TYPE_NAME_MISMATCH", "字段类型与命名不匹配", MetadataQualityRule.DIM_ACCURACY,
                MetadataQualityRule.TARGET_COLUMN, "按字段名中的类型提示检查实际字段类型。", COLUMN_SCOPES));
        add(values, schema("ACCU_ENUM_ANOMALY", "疑似枚举字段值异常", MetadataQualityRule.DIM_ACCURACY,
                MetadataQualityRule.TARGET_COLUMN, "检查疑似枚举字段的去重值数量。", COLUMN_SCOPES,
                integer("maxDistinctValues", "最大枚举值数", "1", "10000", 50)));
        add(values, schema("CONS_CROSS_TABLE_TYPE_MISMATCH", "同名字段跨表类型不一致", MetadataQualityRule.DIM_CONSISTENCY,
                MetadataQualityRule.TARGET_COLUMN, "比较数据源内同名字段的基础类型。", GLOBAL_SOURCE));
        add(values, schema("CONS_CROSS_TABLE_COMMENT_CONFLICT", "同名字段注释冲突", MetadataQualityRule.DIM_CONSISTENCY,
                MetadataQualityRule.TARGET_COLUMN, "比较数据源内同名字段的中文说明。", GLOBAL_SOURCE));
        add(values, schema("TIME_SNAPSHOT_EXPIRED", "快照过期", MetadataQualityRule.DIM_TIMELINESS,
                MetadataQualityRule.TARGET_TABLE, "检查元数据距离采集的天数。", GLOBAL_SOURCE,
                integer("maxAgeDays", "最大快照天数", "1", "3650", 7)));
        add(values, schema("TIME_TABLE_NO_UPDATE", "表估算行数为零", MetadataQualityRule.DIM_TIMELINESS,
                MetadataQualityRule.TARGET_TABLE, "对采集估算行数为零的普通表提示；该证据不能证明长期没有数据更新。", TABLE_SCOPES));
        add(values, schema("TRACE_FK_MISSING", "外键关系缺失", MetadataQualityRule.DIM_TRACEABILITY,
                MetadataQualityRule.TARGET_COLUMN, "检查 *_id 字段是否有元数据关系。", COLUMN_SCOPES));
        add(values, schema("TRACE_ISOLATED_TABLE", "孤立表", MetadataQualityRule.DIM_TRACEABILITY,
                MetadataQualityRule.TARGET_TABLE, "检查表是否参与任一关系。", TABLE_SCOPES));

        add(values, data("DATA_NULL_RATE_HIGH", "列空值率过高", MetadataQualityRule.DIM_COMPLETENESS,
                MetadataQualityRule.TARGET_COLUMN, "以最多 100,000 行只读样本统计空值率。", COLUMN_SCOPES,
                decimal("threshold", "空值率阈值", "0", "1", 0.3)));
        add(values, data("DATA_UNIQUE_VIOLATION", "应唯一列出现重复值", MetadataQualityRule.DIM_ACCURACY,
                MetadataQualityRule.TARGET_COLUMN, "以最多 100,000 行只读样本检查唯一性。默认只检查主键字段，可为手动声明唯一的字段关闭此限制。", COLUMN_SCOPES,
                bool("primaryKeyOnly", "仅检查主键字段", true)));
        add(values, data("DATA_FK_ORPHAN", "外键引用目标不存在", MetadataQualityRule.DIM_CONSISTENCY,
                MetadataQualityRule.TARGET_RELATION, "最多抽取 100,000 条引用端记录，并只读查询目标端索引。", GLOBAL_SOURCE));
        add(values, data("DATA_STALE_TABLE", "表数据长期未更新", MetadataQualityRule.DIM_TIMELINESS,
                MetadataQualityRule.TARGET_TABLE, "使用注册的数据新鲜度检查器及只读连接。", TABLE_SCOPES,
                integer("staleDays", "过期天数", "1", "3650", 30)));
        definitions = Map.copyOf(values);
    }

    public List<Definition> all() {
        return List.copyOf(definitions.values());
    }

    public Definition require(String code) {
        Definition definition = definitions.get(code);
        if (definition == null) {
            throw new BusinessException(400, "该检查模板没有已注册的执行器");
        }
        return definition;
    }

    public Definition find(String code) {
        return definitions.get(code);
    }

    public boolean supportsHandler(String code) {
        return definitions.containsKey(code);
    }

    private static Definition schema(String code, String name, String dimension, String target,
                                     String description, Set<String> scopes, ParameterDefinition... parameters) {
        return new Definition(code, name, dimension, target, MetadataQualityRule.CHECK_TYPE_SCHEMA,
                description, dimension, scopes, List.of(parameters));
    }

    private static Definition data(String code, String name, String dimension, String target,
                                   String description, Set<String> scopes, ParameterDefinition... parameters) {
        return new Definition(code, name, dimension, target, MetadataQualityRule.CHECK_TYPE_DATA,
                description, "DATA", scopes, List.of(parameters));
    }

    private static ParameterDefinition integer(String key, String label, String min, String max, int defaultValue) {
        return new ParameterDefinition(key, label, "INTEGER", min, max, defaultValue);
    }

    private static ParameterDefinition decimal(String key, String label, String min, String max, double defaultValue) {
        return new ParameterDefinition(key, label, "NUMBER", min, max, BigDecimal.valueOf(defaultValue));
    }

    private static ParameterDefinition bool(String key, String label, boolean defaultValue) {
        return new ParameterDefinition(key, label, "BOOLEAN", null, null, defaultValue);
    }

    private static void add(Map<String, Definition> target, Definition definition) {
        target.put(definition.code, definition);
    }
}
