package com.dataocean.module.governance.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 治理模型的启用开关和部署侧资料传输范围。
 */
@Getter
@Setter
@Component
@ConfigurationProperties("dataocean.governance-ai")
public class GovernanceAiProperties {

    /**
     * 是否启用治理模型调用。
     */
    private boolean enabled;

    /**
     * 允许向配置的模型发送资料的数据源 ID。
     */
    private Set<Long> modelSourceIds = Set.of();
}
