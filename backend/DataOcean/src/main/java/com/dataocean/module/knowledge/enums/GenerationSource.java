package com.dataocean.module.knowledge.enums;

import lombok.Getter;

/**
 * 文档版本生成来源枚举
 */
@Getter
public enum GenerationSource {
    MANUAL("人工编辑"),
    AI_GENERATED("AI 生成"),
    ROLLBACK("版本回滚");

    /** 来源描述 */
    private final String description;

    GenerationSource(String description) {
        this.description = description;
    }
}
