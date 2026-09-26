package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.permission.s1.entity.vo.IamS1TablePermissionVO;

import java.util.List;

/** IAM-SIMPLE-1 唯一数据授权计算器；预览直接复用同一 resolve。 */
public interface IamS1DataAuthorizationResolver {

    IamS1DataAuthorizationSnapshot resolve(IamS1DataAuthorizationRequestDTO request);

    /**
     * 完整解析指定表的当前可见字段；共享本次请求加载的授权事实，不使用固定字段探测预算。
     * 每个字段仍按与正式 resolve 相同的 deny、治理、保护和记录条件规则独立判定。
     */
    List<IamS1TablePermissionVO> resolveVisibleColumns(Long userId, Long datasourceId, Long snapshotId,
                                                       String tableName, java.time.LocalDateTime at);

    /** Current IAM-SIMPLE-1 authorization revision for stable candidate catalog construction. */
    Long currentPermissionRevision();

    default IamS1DataAuthorizationSnapshot preview(IamS1DataAuthorizationRequestDTO request) {
        return resolve(request);
    }

    /**
     * 该用户在该数据源上是否至少有一份“主体命中且当前有效”的 ALLOW 授权。
     * <p>
     * 供用户侧资源选择（问数资源声明、访问申请）判断数据源是否可见，
     * 复用与真实查询完全相同的主体匹配（用户 / 角色 / 部门继承）和有效期判定，
     * 不使用后台“负责源”语义，避免普通问数用户没有负责源时看不到任何可选资源。
     * </p>
     *
     * @param at 计算时刻，为空时取当前时间
     */
    boolean hasEffectiveAllowGrant(Long userId, Long datasourceId, java.time.LocalDateTime at);
}
