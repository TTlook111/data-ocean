package com.dataocean.module.datasource.source;

import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.ToString;

/**
 * JDBC 业务源连接采用部署侧策略。用户提交的连接配置不能覆盖这些属性。
 */
@Data
@Component
@ConfigurationProperties("dataocean.datasource")
public class SourceConnectionProperties {

    /**
     * 部署允许运行的目标，格式为解析后的 IP:端口，缺省拒绝所有业务源。
     */
    private Set<String> allowedEndpoints = Set.of();

    /**
     * MySQL 信任库的本地文件路径。未提供时使用 JVM 已配置的信任库。
     */
    private String mysqlTrustStore;

    /**
     * MySQL 信任库的访问密码，只允许从部署环境注入。
     */
    @ToString.Exclude
    private String mysqlTrustStorePassword;

    /**
     * 仅允许受部署管理的独立 CA 验证 MySQL；默认还验证目标地址身份。
     */
    private boolean mysqlVerifyCaOnly;

    /**
     * PostgreSQL 用于验证服务器证书的 PEM CA 文件。
     */
    private String postgresRootCert;

    /**
     * TLS、目标准入或证书策略改变时递增，以使旧测试证据失效。
     */
    private String policyVersion = "1";

    /**
     * 执行窗口和开始日期展示使用的部署业务时区。
     */
    private String timeZone = "Asia/Shanghai";

    /**
     * 当前用于写入凭据及内部会话定位证据的 AES 密钥版本。
     */
    private String credentialKeyVersion = "1";

    /**
     * 版本与只读密钥文件的映射，文件包含 Base64 编码的 256 位 AES 密钥。
     */
    @ToString.Exclude
    private Map<String, String> credentialKeyFiles = Map.of();
}
