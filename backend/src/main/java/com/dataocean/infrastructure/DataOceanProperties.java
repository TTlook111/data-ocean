package com.dataocean.infrastructure;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 只保存配置和受控文件引用，秘密本体不放入可打印的属性对象。 */
@Data
@Validated
@ConfigurationProperties(prefix="dataocean")
public class DataOceanProperties {
    @NotBlank private String secretKeyFile;
    @Min(1) private long sessionIdleSeconds=1800;
    @Min(1) private long sessionAbsoluteSeconds=28800;
    @Min(1) private long reviewSeconds=300;
    @Min(1) private long loginAttemptLimit=10;
    @Min(1) private long loginWindowSeconds=300;
    private boolean outboxEnabled=true;
    private String outboxStream="dataocean:events:domain";
    private String bootstrapUsername;
    private String bootstrapDisplayName;
    private String bootstrapDepartmentCode;
    private String bootstrapDepartmentName;
    private String bootstrapPasswordFile;
}
