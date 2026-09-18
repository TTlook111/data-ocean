package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/**
 * IAM-SIMPLE-1 能力摘要，供前端按 Java 返回的结论显示/隐藏路由、工作区、Tab 和按钮。
 * <p>
 * 前端不是安全边界：该摘要只用于界面可见性，直接调用 API 仍由 Java Controller/Service 强制校验。
 * </p>
 *
 * @param globalFunctions       不依赖数据源负责范围的全局功能码
 * @param datasourceCapabilities 按数据源给出的后台能力（功能与负责源同一绑定）
 * @param queryUse              能否使用问数
 * @param viewSql               能否查看 SQL
 * @param export                能否导出结果
 * @param systemAdmin           是否为唯一受保护系统管理员
 */
public record IamS1CapabilitySnapshotVO(String protocolVersion,
                                        Long userId,
                                        boolean systemAdmin,
                                        List<String> globalFunctions,
                                        List<IamS1DatasourceCapabilityVO> datasourceCapabilities,
                                        boolean queryUse,
                                        boolean viewSql,
                                        boolean export,
                                        Long permissionRevision) {
}
