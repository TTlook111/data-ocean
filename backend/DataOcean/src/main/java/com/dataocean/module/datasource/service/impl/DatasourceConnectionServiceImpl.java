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

@Service
@RequiredArgsConstructor
@Slf4j
public class DatasourceConnectionServiceImpl implements DatasourceConnectionService {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final DatasourceHealthCheckMapper healthCheckMapper;

    @Override
    public DatasourceConnectionTestVO testConnection(String host,
                                                         Integer port,
                                                         String databaseName,
                                                         String charset,
                                                         String username,
                                                         String password) {
        return executeTest(null, host, port, databaseName, charset, username, password, null);
    }

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
            result = DatasourceConnectionTestVO.builder()
                    .success(false)
                    .responseTimeMs(elapsedMs)
                    .serverVersion(null)
                    .message(normalizeError(exception.getMessage()))
                    .build();
        }
        if (checkType != null) {
            recordHealthCheck(datasourceId, checkType, result);
        }
        return result;
    }

    private String jdbcUrl(String host, Integer port, String databaseName, String charset) {
        String resolvedEncoding = resolveJdbcEncoding(charset);
        return "jdbc:mysql://" + host + ":" + port + "/" + databaseName
                + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                + "&connectTimeout=" + CONNECT_TIMEOUT_MS
                + "&socketTimeout=" + CONNECT_TIMEOUT_MS
                + "&characterEncoding=" + resolvedEncoding;
    }

    private String resolveJdbcEncoding(String charset) {
        if (!StringUtils.hasText(charset) || "utf8mb4".equalsIgnoreCase(charset)) {
            return "utf8";
        }
        return charset;
    }

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

    private long elapsedMs(long started) {
        return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
    }

    private String normalizeError(String message) {
        if (!StringUtils.hasText(message)) {
            return "连接失败";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
