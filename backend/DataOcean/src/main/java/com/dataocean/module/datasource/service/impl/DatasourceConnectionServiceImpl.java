package com.dataocean.module.datasource.service.impl;

import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.entity.DatasourceHealthCheck;
import com.dataocean.module.datasource.entity.vo.DatasourceConnectionTestVO;
import com.dataocean.module.datasource.mapper.DatasourceHealthCheckMapper;
import com.dataocean.module.datasource.service.DatasourceConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据源连接测试服务实现类
 * <p>
 * 通过 JDBC 直连方式测试数据源的连通性，执行 SELECT 1 验证连接是否正常，
 * 并记录响应时间和数据库版本信息。支持可选的健康检查记录持久化。
 * </p>
 *
 * @author dataocean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourceConnectionServiceImpl implements DatasourceConnectionService {

    /** 连接超时时间（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final DatasourceHealthCheckMapper healthCheckMapper;

    /**
     * {@inheritDoc}
     */
    @Override
    public DatasourceConnectionTestVO testConnection(String host,
                                                         Integer port,
                                                         String databaseName,
                                                         String charset,
                                                         String username,
                                                         String password) {
        // 不记录健康检查（checkType 为 null）
        return executeTest(null, host, port, databaseName, charset, username, password, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatasourceConnectionTestVO testConnection(Datasource datasource, String username, String password, String checkType) {
        DatasourceConnectionTestVO result = executeTest(
                datasource.getId(),
                datasource.getHost(),
                datasource.getPort(),
                datasource.getDatabaseName(),
                datasource.getCharset(),
                username,
                password,
                checkType
        );
        return result;
    }

    /**
     * 执行实际的 JDBC 连接测试
     * <p>
     * 通过 DriverManager 建立连接并执行 SELECT 1 验证连通性，
     * 记录响应时间和数据库版本。若指定了 checkType，则持久化健康检查记录。
     * </p>
     *
     * @param datasourceId 数据源 ID（可为 null，表示新建测试）
     * @param host         主机地址
     * @param port         端口号
     * @param databaseName 数据库名称
     * @param charset      字符集
     * @param username     用户名
     * @param password     密码（明文）
     * @param checkType    检查类型（为 null 时不记录健康检查）
     * @return 连接测试结果
     */
    private DatasourceConnectionTestVO executeTest(Long datasourceId,
                                                       String host,
                                                       Integer port,
                                                       String databaseName,
                                                       String charset,
                                                       String username,
                                                       String password,
                                                       String checkType) {
        long started = System.nanoTime();
        DatasourceConnectionTestVO result;
        try (Connection connection = DriverManager.getConnection(
                jdbcUrl(host, port, databaseName, charset),
                username,
                password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            resultSet.next();
            long elapsedMs = elapsedMs(started);
            // 连接成功，构建成功结果
            result = DatasourceConnectionTestVO.builder()
                    .success(true)
                    .responseTimeMs(elapsedMs)
                    .serverVersion(connection.getMetaData().getDatabaseProductVersion())
                    .message("连接成功")
                    .build();
        } catch (Exception exception) {
            long elapsedMs = Math.min(elapsedMs(started), CONNECT_TIMEOUT_MS);
            log.warn("数据源连接测试失败 datasourceId={} host={} port={} database={} reason={}",
                    datasourceId, host, port, databaseName, exception.getMessage());
            // 连接失败，构建失败结果
            result = DatasourceConnectionTestVO.builder()
                    .success(false)
                    .responseTimeMs(elapsedMs)
                    .serverVersion(null)
                    .message(normalizeError(exception.getMessage()))
                    .build();
        }
        // 若指定了检查类型，持久化健康检查记录
        if (checkType != null) {
            recordHealthCheck(datasourceId, checkType, result);
        }
        return result;
    }

    /**
     * 构建 JDBC 连接 URL
     *
     * @param host         主机地址
     * @param port         端口号
     * @param databaseName 数据库名称
     * @param charset      字符集
     * @return 完整的 JDBC URL
     */
    private String jdbcUrl(String host, Integer port, String databaseName, String charset) {
        String resolvedEncoding = resolveJdbcEncoding(charset);
        return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + CONNECT_TIMEOUT_MS
                + "&characterEncoding=" + resolvedEncoding
                + "&allowLoadLocalInfile=false"
                + "&autoDeserialize=false"
                + "&allowUrlInLocalInfile=false";
    }

    /**
     * 将数据库字符集转换为 JDBC 编码参数
     * <p>
     * utf8mb4 在 JDBC 中对应 utf8 编码参数。
     * </p>
     *
     * @param charset 数据库字符集
     * @return JDBC characterEncoding 参数值
     */
    private String resolveJdbcEncoding(String charset) {
        if (!StringUtils.hasText(charset) || "utf8mb4".equalsIgnoreCase(charset)) {
            return "utf8";
        }
        return charset;
    }

    /**
     * 持久化健康检查记录到数据库
     *
     * @param datasourceId 数据源 ID
     * @param checkType    检查类型
     * @param result       连接测试结果
     */
    private void recordHealthCheck(Long datasourceId, String checkType, DatasourceConnectionTestVO result) {
        DatasourceHealthCheck check = new DatasourceHealthCheck();
        check.setDatasourceId(datasourceId);
        check.setCheckType(checkType);
        check.setSuccess(Boolean.TRUE.equals(result.getSuccess()) ? 1 : 0);
        check.setResponseTimeMs(result.getResponseTimeMs() == null ? null : Math.toIntExact(Math.min(result.getResponseTimeMs(), Integer.MAX_VALUE)));
        check.setServerVersion(result.getServerVersion());
        check.setErrorMessage(Boolean.TRUE.equals(result.getSuccess()) ? null : result.getMessage());
        healthCheckMapper.insert(check);
    }

    /**
     * 计算从起始时间到当前的耗时（毫秒）
     *
     * @param started 起始时间（纳秒）
     * @return 耗时毫秒数，最小为 1
     */
    private long elapsedMs(long started) {
        return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 规范化错误信息
     * <p>
     * 空消息返回默认提示，过长消息截断至 500 字符。
     * </p>
     *
     * @param message 原始错误信息
     * @return 规范化后的错误信息
     */
    private String normalizeError(String message) {
        if (!StringUtils.hasText(message)) {
            return "连接失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
