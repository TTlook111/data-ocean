package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.IamS1SnapshotOption;
import com.dataocean.module.permission.s1.entity.vo.IamS1ColumnOptionVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1TableOptionVO;

import java.util.List;

/**
 * IAM-SIMPLE-1 用户侧资源选择（问数资源声明、访问申请）。
 * <p>
 * 与后台“负责源”语义严格区分：
 * </p>
 * <ul>
 *   <li><b>QUERY</b>：只列出该用户存在“主体命中且当前有效 ALLOW 授权”的数据源，
 *       判定复用统一 Resolver 的同一套主体匹配与有效期逻辑；普通问数用户不需要后台负责源。</li>
 *   <li><b>APPLY</b>：列出已启用且存在已发布快照的数据源，只提供申请所需的安全名称，
 *       不开放后台目录、采样数据或他人权限。</li>
 * </ul>
 * 两者都要求“使用问数”功能；返回内容一律不含连接信息、密码或业务记录。
 */
public interface IamS1UserResourceService {

    /** 用户可选数据源。scope 取 QUERY 或 APPLY，缺省按 QUERY。 */
    List<IamS1DatasourceRefVO> datasources(Long userId, String scope);

    /** 已发布快照。 */
    List<IamS1SnapshotOption> publishedSnapshots(Long userId, String scope, Long datasourceId);

    /** 已发布快照中的表；不可查询/不可申请的表标记为不可选。 */
    List<IamS1TableOptionVO> tables(Long userId, String scope, Long datasourceId, Long snapshotId);

    /** 表字段；隐藏字段或治理状态不允许的字段标记为不可选。 */
    List<IamS1ColumnOptionVO> columns(Long userId, String scope, Long datasourceId, Long snapshotId,
                                      String tableName);
}
