package com.dataocean.module.glossary.service;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 业务术语「源/全」混合范围的唯一落实点。
 *
 * <p>{@code glossary:view} / {@code glossary:manage} / {@code glossary:approve} 三个码在 B0 被标为
 * 「源/全」混合：同一功能在“术语关联了数据源”时按源校验、未关联时按全局校验。批次 5 已把语义定稿为：</p>
 *
 * <ul>
 *   <li><b>未关联任何数据源</b>：只校验功能本身，**不因为“未绑定”推导任何数据源权限**；</li>
 *   <li><b>已关联一个或多个数据源</b>：查看只返回调用者负责源内的关联字段；写操作解析术语当前关联的
 *       全部数据源，**逐个**要求同一绑定上的“功能 + 负责源”，任意一个无权则整体拒绝。</li>
 * </ul>
 *
 * <p><b>范围状态是三态，不是“空集合即未绑定”。</b>术语可能有一个已经存在、但目标实体丢失或
 * 缺少数据源归属的关联：这种情况下关联源集合同样是空的，但它**不是**“未绑定”，
 * 如果按空集合处理就会把「归属损坏」错误升级成「合法未绑定、全局放行」。因此：</p>
 *
 * <ul>
 *   <li>{@link ScopeStatus#UNBOUND}：确实没有任何关联关系 → 按全局功能；</li>
 *   <li>{@link ScopeStatus#BOUND}：关联完整，得到数据源集合 → 逐源校验；</li>
 *   <li>{@link ScopeStatus#BROKEN}：存在关联，但实体不存在、读取失败或缺少数据源归属 →
 *       fail-closed：列表不返回该术语，写/审核/删除/关联一律 409。</li>
 * </ul>
 *
 * <p>授权判定一律复用 {@code IamS1AdminGuard} / {@code IamS1CapabilityService}，
 * 本类不复制任何授权 SQL。</p>
 */
public interface GlossaryScopeService {

    /** 术语「源/全」范围的三种状态。 */
    enum ScopeStatus {
        /** 确实没有任何关联关系。 */
        UNBOUND,
        /** 关联完整，已解析出数据源集合。 */
        BOUND,
        /** 存在关联，但目标实体不存在、读取失败或缺少数据源归属。 */
        BROKEN
    }

    /**
     * 一个术语（或术语表汇总）的范围状态。
     *
     * <p>`datasourceIds` 只在 {@link ScopeStatus#BOUND} 时有意义；BROKEN 一律为空集合，
     * 且**不得**被当作“未绑定”处理。</p>
     */
    record Scope(ScopeStatus status, Set<Long> datasourceIds) {

        public static Scope unbound() {
            return new Scope(ScopeStatus.UNBOUND, Set.of());
        }

        public static Scope bound(Set<Long> datasourceIds) {
            return new Scope(ScopeStatus.BOUND, Set.copyOf(datasourceIds));
        }

        public static Scope broken() {
            return new Scope(ScopeStatus.BROKEN, Set.of());
        }

        public boolean isBroken() {
            return status == ScopeStatus.BROKEN;
        }
    }

    /** 调用者在指定功能上负责的数据源 ID（要求功能与负责源在同一条启用绑定上）。 */
    Set<Long> visibleDatasourceIds(Long userId, String functionCode);

    /** 单个术语的范围状态。 */
    Scope termScope(Long termId);

    /**
     * 批量：每个术语 → 它的范围状态。
     *
     * <p>一次关系查询取回全部术语的关联行、再一次批量取实体，**不按术语逐个查**（避免 N+1）。
     * 结果包含传入的每个术语 id。</p>
     */
    Map<Long, Scope> termScopes(Collection<Long> termIds);

    /**
     * 术语表的范围状态：其下**任一**术语 BROKEN 即 BROKEN；全部未关联则 UNBOUND；
     * 否则是各已绑定术语关联源的并集。术语表自身不携带数据源归属。
     */
    Scope glossaryScope(Long glossaryId);

    /** 批量：实体 ID → 归属数据源；实体不存在或没有归属时不出现在结果里。 */
    Map<Long, Long> entityDatasourceIds(Collection<Long> entityIds);

    /**
     * 写/审操作的动态范围校验。
     *
     * <ul>
     *   <li>{@link ScopeStatus#BROKEN} → 直接 409，不做任何写入；</li>
     *   <li>{@link ScopeStatus#UNBOUND} → 只校验功能（切面已完成），不推导任何数据源权限；</li>
     *   <li>{@link ScopeStatus#BOUND} → 逐个校验，任意一个无权立即抛出 403。</li>
     * </ul>
     */
    void requireWritableScope(Long userId, String functionCode, Scope scope);

    /**
     * 可见性判定（纯函数）：BROKEN 不可见；UNBOUND 按全局语义可见；
     * BOUND 至少要有一个关联源在可见集合内。
     */
    static boolean visibleIn(Scope scope, Set<Long> visibleDatasourceIds) {
        if (scope == null || scope.isBroken()) {
            return false;
        }
        if (scope.status() == ScopeStatus.UNBOUND) {
            return true;
        }
        if (visibleDatasourceIds == null || visibleDatasourceIds.isEmpty()) {
            return false;
        }
        for (Long datasourceId : scope.datasourceIds()) {
            if (visibleDatasourceIds.contains(datasourceId)) {
                return true;
            }
        }
        return false;
    }
}
