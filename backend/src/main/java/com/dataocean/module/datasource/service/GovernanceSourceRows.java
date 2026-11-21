package com.dataocean.module.datasource.service;

import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.source.SourceConnection;
import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceSecretCodec;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 治理只使用由成功目录生成的窄范围读取，不接受模型 SQL。
 */
@Service
@RequiredArgsConstructor
public class GovernanceSourceRows {

    /**
     * 读取同一源当前固定的连接及只读凭据。
     */
    private final DatasourceMapper sources;

    /**
     * 与采集共享批准端点、TLS、认证及只读连接策略。
     */
    private final SourceJdbc jdbc;

    /**
     * 仅在打开连接时解密当次源库凭据。
     */
    private final SourceSecretCodec cipher;

    /**
     * 按固定数据库方言引用元数据标识，值和分页参数由驱动绑定。
     */
    public String statement(GovernanceCatalog.Source source, ObjectRef object, String field) {
        var settings = sources.revision(source.id(), source.configurationId()).configuration();
        boolean mysql = "MYSQL".equals(settings.getDatabaseType());
        String schema = mysql ? settings.getDatabaseName() : object.schema();
        return "SELECT " + (mysql ? "/*+ MAX_EXECUTION_TIME(3000) */ " : "")
                + identifier(field, mysql) + " FROM " + identifier(schema, mysql) + "."
                + identifier(object.name(), mysql) + " LIMIT ? OFFSET ?";
    }

    /**
     * 仅运行服务端生成的查询，使用短时限、定长值及只读账户。
     */
    public List<String> read(GovernanceCatalog.Source source, String sql, int limit, int offset) throws SQLException {
        var revision = sources.revision(source.id(), source.configurationId());
        var config = revision.configuration();
        int seconds = Math.min(3, revision.limits().getQueryTimeoutSeconds());
        var values = new ArrayList<String>();
        try (var connection = open(source, config, seconds);
                var query = connection.prepareStatement(sql)) {
            // 两层限制同时覆盖 JDBC 和实际源库执行。
            query.setMaxRows(limit);
            query.setQueryTimeout(seconds);
            query.setInt(1, limit);
            query.setInt(2, offset);
            try (var rows = query.executeQuery()) {
                // 仅返回受控的短标量，原始值不写日志、回执或平台库。
                while (rows.next()) {
                    String value = rows.getString(1);
                    values.add(safeValue(value));
                }
            }
        }
        return values;
    }

    /**
     * 独立执行源数据库的实际去重计数，连接字段来自治理和当前目录。
     */
    public Count countDistinct(
            GovernanceCatalog.Source source,
            ObjectRef primary,
            String key,
            ObjectRef detail,
            String foreign,
            String category,
            String value)
            throws SQLException {
        var revision = sources.revision(source.id(), source.configurationId());
        var config = revision.configuration();
        boolean mysql = "MYSQL".equals(config.getDatabaseType());
        int seconds = Math.min(3, revision.limits().getQueryTimeoutSeconds());
        String sql = "SELECT " + (mysql ? "/*+ MAX_EXECUTION_TIME(3000) */ " : "")
                + "COUNT(DISTINCT a." + identifier(key, mysql) + ") FROM "
                + table(primary, config.getDatabaseName(), mysql) + " a JOIN "
                + table(detail, config.getDatabaseName(), mysql) + " b ON a."
                + identifier(key, mysql) + "=b." + identifier(foreign, mysql)
                + " WHERE a." + identifier(category, mysql) + "=?";
        try (var connection = open(source, config, seconds);
                var statement = connection.prepareStatement(sql)) {
            // 绑定分类值，实际返回限制为一个聚合值。
            statement.setString(1, value);
            statement.setQueryTimeout(seconds);
            statement.setMaxRows(1);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    // 缺少实际查询结果不能视为零。
                    throw new SQLException("MISSING_COUNT");
                }
                return new Count(rows.getLong(1), sql);
            }
        }
    }

    /**
     * 使用所在源正确的数据库或模式分隔名。
     */
    private String table(ObjectRef table, String database, boolean mysql) {
        return identifier(mysql ? database : table.schema(), mysql) + "." + identifier(table.name(), mysql);
    }

    /**
     * 实际源返回的单值及不含参数的查询模板。
     */
    public record Count(long value, String statement) {}

    /**
     * 使用采集模块的固定只读连接和密钥保护。
     */
    private Connection open(GovernanceCatalog.Source source, SourceConfigurationDTO config, int seconds)
            throws SQLException {
        var target = new SourceConnection(
                Type.valueOf(config.getDatabaseType()),
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getSchemas(),
                config.getAccount(),
                seconds);
        var credential = sources.credential(source.id(), source.configurationId());
        String password = cipher.decrypt(
                credential.ciphertext(), "datasource:credential:" + source.id(), credential.keyVersion());
        return jdbc.open(target, password, "do-sample-" + UUID.randomUUID());
    }

    /**
     * 转义实际目录标识内的引号，语句不能拼入模型或浏览器内容。
     */
    private String identifier(String value, boolean mysql) {
        String quote = mysql ? "\u0060" : "\"";
        return quote + Objects.requireNonNull(value).replace(quote, quote + quote) + quote;
    }

    /**
     * 明确披露的短字段也遮盖疑似联系方式、长标识及自由文本。
     */
    private String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (value.length() > 32 || value.matches(".*\\d{7,}.*") || !value.matches("[\\p{L}\\p{N} .:_/\\-]+")) {
            return "[已遮盖]";
        }
        return value;
    }
}
