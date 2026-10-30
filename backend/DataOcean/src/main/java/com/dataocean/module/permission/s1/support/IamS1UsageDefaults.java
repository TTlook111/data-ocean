package com.dataocean.module.permission.s1.support;

import com.dataocean.module.permission.s1.IamS1Constants;
import com.dataocean.module.permission.s1.enums.IamS1ColumnUsage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * IAM-SIMPLE-1 问数资源声明的默认使用位置（usage）。
 * <p>
 * 自然语言问数在提交时无法预知模型把字段放在投影、过滤还是关联位置，因此资源声明需要一个明确、
 * 可解释的默认值。这里默认放开 B3 在 AST 层强制校验的三个位置（PROJECTION/FILTER/JOIN）：
 * 字段能否被暴露仍由 PROJECTION + 字段保护决定，而不是靠“少声明几个位置”来收紧。
 * </p>
 * <p>
 * 前端 `/query/iam-s1` 必须发送与 {@link #DEFAULT_QUERY_USAGES} 一致的默认集合，否则 Java 的
 * {@code requireExplicitUsages} 会以“字段 usage 缺失”直接拒绝提交。两侧口径必须同步修改。
 * </p>
 */
public final class IamS1UsageDefaults {

    /** 默认放开的位置：投影、过滤、关联。 */
    public static final Set<IamS1ColumnUsage> DEFAULT_QUERY_USAGES =
            Collections.unmodifiableSet(EnumSet.of(IamS1ColumnUsage.PROJECTION,
                    IamS1ColumnUsage.FILTER, IamS1ColumnUsage.JOIN));

    /** 排序、分组、聚合与子查询位置：B3 暂缓在 AST 层强制，默认不声明，由调用方显式选择。 */
    public static final Set<IamS1ColumnUsage> EXTENDED_QUERY_USAGES =
            Collections.unmodifiableSet(EnumSet.of(IamS1ColumnUsage.ORDER, IamS1ColumnUsage.GROUP,
                    IamS1ColumnUsage.HAVING, IamS1ColumnUsage.FUNCTION, IamS1ColumnUsage.SUBQUERY));

    private IamS1UsageDefaults() {
    }

    /** 默认使用位置（不可变副本，供直接写入请求 DTO）。 */
    public static Set<IamS1ColumnUsage> defaults() {
        return DEFAULT_QUERY_USAGES;
    }

    /** 默认位置 + 扩展位置；调用方选择“允许排序/分组/聚合/子查询”时使用。 */
    public static Set<IamS1ColumnUsage> extended() {
        return Collections.unmodifiableSet(EnumSet.copyOf(join()));
    }

    /**
     * 按字段保护状态生成使用位置，必须与统一 Resolver 的判定一致：
     * <ul>
     *   <li>MASKED：只能直接投影。B2 Resolver 对脱敏字段强制“仅 PROJECTION”，
     *       多声明 FILTER/JOIN 会被判为 {@code MASKED_FIELD_USAGE_FORBIDDEN}。</li>
     *   <li>HIDDEN：不参与问数，返回空集合。</li>
     *   <li>NORMAL：返回默认位置。</li>
     * </ul>
     */
    public static Set<IamS1ColumnUsage> forProtectionLevel(String protectionLevel) {
        if (IamS1Constants.PROTECTION_MASKED.equals(protectionLevel)) {
            return Collections.unmodifiableSet(EnumSet.of(IamS1ColumnUsage.PROJECTION));
        }
        if (IamS1Constants.PROTECTION_HIDDEN.equals(protectionLevel)) {
            return Set.of();
        }
        return DEFAULT_QUERY_USAGES;
    }

    /** 默认位置加上扩展位置；脱敏字段仍只能直接投影（扩展位置对脱敏字段无效）。 */
    public static Set<IamS1ColumnUsage> forProtectionLevel(String protectionLevel, boolean extended) {
        if (IamS1Constants.PROTECTION_MASKED.equals(protectionLevel)) {
            return forProtectionLevel(protectionLevel);
        }
        return extended ? extended() : forProtectionLevel(protectionLevel);
    }

    private static Set<IamS1ColumnUsage> join() {
        EnumSet<IamS1ColumnUsage> all = EnumSet.copyOf(DEFAULT_QUERY_USAGES);
        all.addAll(EXTENDED_QUERY_USAGES);
        return all;
    }
}
