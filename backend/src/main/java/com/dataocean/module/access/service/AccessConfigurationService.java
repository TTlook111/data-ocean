package com.dataocean.module.access.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.access.entity.dto.DepartmentAccessDTO;
import com.dataocean.module.access.entity.dto.OutputRuleDTO;
import com.dataocean.module.access.entity.dto.ResponsibilityDTO;
import com.dataocean.module.access.entity.query.AccessListQuery;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.entity.vo.DepartmentAccessVO;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.access.entity.vo.ResponsibilityVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;

/**
 * 访问授权共用规则、责任和部门配置的业务边界。
 */
public interface AccessConfigurationService {

    /**
     * 分页搜索共享规则名称和全文。
     */
    PageVO<OutputRuleVO> rules(AccessListQuery query);

    /**
     * 保存新增或已有规则，两类动作各自核权。
     */
    MutationVO saveRule(Long id, String command, OutputRuleDTO input);

    /**
     * 核对共享规则的新旧正文和实际引用范围。
     */
    ImpactPreviewVO previewRule(long id, OutputRuleDTO input);

    /**
     * 分页读取用于当前办理的稳定源引用。
     */
    PageVO<AccessSourceOptionVO> sourceOptions(AccessListQuery query);

    /**
     * 读取当前可承接审批责任的人员。
     */
    PageVO<UserOptionVO> approvers(String keyword, PageQuery query);

    /**
     * 读取来源责任与当前有效性。
     */
    PageVO<ResponsibilityVO> responsibilities(AccessListQuery query);

    /**
     * 保存指定源的主、替代责任。
     */
    MutationVO saveResponsibility(long source, String command, ResponsibilityDTO input);

    /**
     * 核对责任变更对未办理子项的影响。
     */
    ImpactPreviewVO previewResponsibility(long id, ResponsibilityDTO input);

    /**
     * 读取部门配置、直属成员和相应版本。
     */
    DepartmentAccessVO department(long id);

    /**
     * 在当前部门版本下保存或结束初始来源。
     */
    MutationVO saveDepartment(long id, String command, DepartmentAccessDTO input);

    /**
     * 核对部门配置版本、规则全文及当前影响成员。
     */
    ImpactPreviewVO previewDepartment(long id, DepartmentAccessDTO input);
}
