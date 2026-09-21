package com.dataocean.module.permission.s1.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S1 角色视图。技术码只在排查详情展示，首屏使用中文名称、作用与能力摘要。
 *
 * @param capabilitySummary 中文能力摘要，例如“可以问数、查看 SQL”
 */
public record IamS1RoleVO(Long id,
                          String roleCode,
                          String roleName,
                          String description,
                          boolean enabled,
                          boolean protectedRole,
                          boolean builtIn,
                          List<String> functionCodes,
                          List<String> functionNames,
                          String capabilitySummary,
                          long memberCount,
                          LocalDateTime createdAt) {
}
