package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.vo.IamS1DataGrantVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1FieldProtectionItemVO;

import java.util.List;

/**
 * IAM-SIMPLE-1 授权配置与字段保护的只读查询。
 * <p>
 * 页面只展示中文授权摘要、字段保护状态和来源，不展示 scope/effect/policy/revision 等技术词。
 * 授权列表不代表查询权；预览必须调用与真实查询同一个统一 Resolver。
 * </p>
 */
public interface IamS1GrantQueryService {

    /** 按数据源列出数据授权（可再按主体、表、状态筛选）。 */
    List<IamS1DataGrantVO> listGrants(Long operatorUserId, Long datasourceId, String subjectType,
                                      Long subjectId, String tableName, String status);

    /** 单条数据授权详情。 */
    IamS1DataGrantVO getGrant(Long operatorUserId, Long grantId);

    /** 字段保护列表。 */
    List<IamS1FieldProtectionItemVO> listFieldProtections(Long operatorUserId, Long datasourceId,
                                                          Long snapshotId, String tableName);
}
