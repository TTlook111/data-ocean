package com.dataocean.module.governance.constant;

/**
 * 治理状态常量。
 * <p>
 * 统一定义元数据治理状态，避免在多个实体类中重复定义。
 * </p>
 */
public final class GovernanceStatuses {

    /** 已发现：元数据刚被采集，尚未进行治理 */
    public static final String DISCOVERED = "DISCOVERED";

    /** 正常：元数据已通过治理检查 */
    public static final String NORMAL = "NORMAL";

    /** 已推荐：系统推荐的治理状态变更 */
    public static final String RECOMMENDED = "RECOMMENDED";

    /** 敏感：包含敏感数据的字段 */
    public static final String SENSITIVE = "SENSITIVE";

    /** 已弃用：不再使用的元数据 */
    public static final String DEPRECATED = "DEPRECATED";

    /** 已阻止：被标记为不可用的元数据 */
    public static final String BLOCKED = "BLOCKED";

    private GovernanceStatuses() {
        // 常量类不允许实例化
    }
}
