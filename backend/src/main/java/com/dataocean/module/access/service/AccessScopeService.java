package com.dataocean.module.access.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessGrantVO;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.DepartmentGrantVO;
import com.dataocean.module.access.entity.vo.GrantDirectoryVO;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

import java.util.List;

/**
 * 实时授权范围和已生效来源的撤销边界。
 */
public interface AccessScopeService {

    /**
     * 服务端按人员和当前有效来源计算数据源与输出限制。
     */
    List<AccessScopeVO> current(long user);

    /**
     * 校验源访问并返回权威当前规则，供受控业务执行使用。
     */
    AccessScopeVO requireSource(long user, long source);

    /**
     * 按真实审批事实查询个人授权来源。
     */
    PageVO<AccessGrantVO> grants(AccessListQuery query);

    /**
     * 同一分页回看申请与部门产生的实际授权。
     */
    PageVO<GrantDirectoryVO> grantDirectory(AccessListQuery query);

    /**
     * 由本人或有权人员追溯个别人员的个人来源历史。
     */
    PageVO<AccessGrantVO> userGrants(long user, AccessListQuery query);

    /**
     * 读取直属来源的实际生效及退出时间，可限定具体人员。
     */
    PageVO<DepartmentGrantVO> departmentGrants(Long user, AccessListQuery query);

    /**
     * 预览本次撤销影响，保留其他来源和规则提示。
     */
    ImpactPreviewVO previewRevoke(long id, int version, String reason);

    /**
     * 提交核对过的个人来源撤销，联合来源同时结束。
     */
    MutationVO revoke(long id, String command, VersionActionDTO input);

    /**
     * 账号停用或归档时，在原 IAM 事务内结束个人来源。
     */
    void terminateForUser(long user, long actor, String reason);

    /**
     * 归档部门前核对是否还保留未结束的初始配置。
     */
    boolean hasDepartmentConfiguration(long department);

    /**
     * 为来源生命周期计算仍引用此源的部门与个人配置。
     */
    List<ImpactCountVO> sourceImpact(long source);
}
