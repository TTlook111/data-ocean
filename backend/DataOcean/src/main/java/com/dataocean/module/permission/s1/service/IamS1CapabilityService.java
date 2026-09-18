package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.vo.IamS1CapabilitySnapshotVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1GrantTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1RoleTemplateVO;
import com.dataocean.module.permission.s1.entity.vo.IamS1SubjectOptionVO;

import java.util.List;

/**
 * IAM-SIMPLE-1 能力摘要与中文模板读取服务。
 * <p>
 * 能力摘要只读 S1 事实，供前端按 Java 结论显示/隐藏路由、Tab 和按钮；
 * 它不是安全边界，直接 API 调用仍由 Java Controller/Service 校验。
 * </p>
 */
public interface IamS1CapabilityService {

    /** 当前用户的能力摘要。 */
    IamS1CapabilitySnapshotVO snapshot(Long userId);

    /** 首期角色模板（中文名称、默认功能与能力摘要）。 */
    List<IamS1RoleTemplateVO> roleTemplates();

    /** 常见数据授权模板（中文说明，避免技术词）。 */
    List<IamS1GrantTemplateVO> grantTemplates();

    /** 当前用户有后台负责范围的数据源。 */
    List<IamS1DatasourceRefVO> responsibleDatasources(Long userId);

    /** 授权表单可选的全部启用数据源；只有系统管理员返回全部，其他用户返回负责范围。 */
    List<IamS1DatasourceRefVO> selectableDatasources(Long userId);

    /** 表单选择对象：用户/角色/部门的最小名称信息。 */
    List<IamS1SubjectOptionVO> subjectOptions(Long operatorUserId, String subjectType, String keyword);
}
