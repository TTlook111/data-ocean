package com.dataocean.module.iam.entity.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationChangedDTO {

    /**
     * 平台当前功能与组织配置的权威安全版本。
     */
    private String securityVersion;

    /**
     * 业务变更类型，用于明确后续影响处理。
     */
    private String changeKind;

    /**
     * 本次权限变化实际涉及的可信主体引用。
     */
    private List<SubjectRefDTO> subjectRefs;

    /**
     * 本次事件实际涉及的访问来源标识集合。
     */
    private List<String> sourceIds;

    /**
     * 事件对主体及来源范围的明确影响口径。
     */
    private String impactMode;
}
