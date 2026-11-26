package com.dataocean.module.datasource.source;

import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceMetadata.Field;
import com.dataocean.module.datasource.source.SourceMetadata.Key;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Session;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 执行固定的源库元数据查询和同账号会话观察。连接测试和采集使用同一连接策略。
 */
@Component
@RequiredArgsConstructor
public class SourceJdbc {

    /**
     * 根据源库实际口径验证端点和证书。
     */
    private final SourceConnectionProperties policy;

    /**
     * 确认 MySQL 授权只包括读取基础结构所需的权限，带角色和授予权的账号需另行核实。
     */
    private static final Pattern MYSQL_READ_GRANT =
            Pattern.compile("(?i)^GRANT (?:SELECT|SHOW VIEW|USAGE)(?:, (?:SELECT|SHOW VIEW))* ON .+ TO .+$");

    /**
     * 用真实列的字符集或排序规则比较两个参数，只访问目录与标量，不读取业务行。
     */
    public boolean compareText(
            Connection connection, Type type, ObjectRef object, String field, String first, String second, int seconds)
            throws SQLException {
        boolean mysql = type == Type.MYSQL;
        String metadata = mysql
                ? "SELECT CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS"
                : "SELECT collation_schema, collation_name, data_type" + " FROM information_schema.columns";
        String expression;
        try (var statement = connection.prepareStatement(
                metadata + " WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            // 用事实目录标识定位列，值不拼入元数据查询。
            statement.setQueryTimeout(seconds);
            statement.setString(1, mysql ? object.catalog() : object.schema());
            statement.setString(2, object.name());
            statement.setString(3, field);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("当前源列缺少比较规则");
                }
                expression = mysql ? mysqlTextExpression(row) : postgresTextExpression(row);
                if (row.next()) {
                    throw new SQLException("当前源列身份不唯一");
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT " + expression + " = " + expression)) {
            // 只对两个已绑定的参数调用数据库自己的等值语义。
            statement.setQueryTimeout(seconds);
            statement.setString(1, first);
            statement.setString(2, second);
            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("源库未返回比较结果");
                }
                var equal = row.getBoolean(1);
                if (row.wasNull() || statement.getWarnings() != null) {
                    throw new SQLException("源库无法确定比较结果");
                }
                return equal;
            }
        }
    }

    /**
     * MySQL 的常量按列的字符集和排序规则转换，包括大小写及空格规则。
     */
    private String mysqlTextExpression(ResultSet metadata) throws SQLException {
        var charset = metadata.getString(1);
        var collation = metadata.getString(2);
        if (charset == null
                || collation == null
                || !charset.matches("[A-Za-z0-9_]+")
                || !collation.matches("[A-Za-z0-9_]+")) {
            // 仅允许服务端实际返回的字符集标识进入 SQL。
            throw new SQLException("当前 MySQL 列的字符比较规则不可用");
        }
        return "(CAST(? AS CHAR CHARACTER SET " + charset + ") COLLATE " + collation + ")";
    }

    /**
     * PostgreSQL 保留定长字符的比较类型和目录指定的排序模式。
     */
    private String postgresTextExpression(ResultSet metadata) throws SQLException {
        var schema = metadata.getString(1);
        var collation = metadata.getString(2);
        var dataType = metadata.getString(3);
        String cast;
        if ("character".equals(dataType)) {
            // 保留定长字符的运算语义，又不截断用户完整参数。
            cast = "CAST(? AS pg_catalog.bpchar)";
        } else if ("text".equals(dataType) || "character varying".equals(dataType)) {
            cast = "CAST(? AS TEXT)";
        } else {
            // 不对未支持的原生类型猜测字符串运算规则。
            throw new SQLException("当前 PostgreSQL 列的字符比较类型不可用");
        }
        if (collation == null) {
            // 目录的空排序模式表示使用数据库默认规则。
            return cast;
        }
        if (schema == null) {
            throw new SQLException("当前列的排序规则缺少模式");
        }
        return "(" + cast + " COLLATE \"" + schema.replace("\"", "\"\"") + "\".\"" + collation.replace("\"", "\"\"")
                + "\")";
    }

    /**
     * 连接当前目标，强制 TLS 和认证证书，密码仅传给 JDBC 属性。
     */
    public Connection open(SourceConnection source, String password, String marker) throws SQLException {
        String host = verifiedAddress(source);

        Properties properties = new Properties();
        properties.setProperty("user", source.account());
        properties.setProperty("password", password);

        String protocol;
        if (source.type() == Type.MYSQL) {
            // MySQL 的实例证据用连接握手属性关联，证书策略统一由部署决定。
            protocol = "jdbc:mysql://";
            properties.setProperty("sslMode", policy.isMysqlVerifyCaOnly() ? "VERIFY_CA" : "VERIFY_IDENTITY");
            properties.setProperty("connectTimeout", "5000");
            properties.setProperty("socketTimeout", Integer.toString(source.queryTimeoutSeconds() * 1000));
            properties.setProperty("useInformationSchema", "true");
            properties.setProperty("connectionAttributes", "dataocean_marker:" + marker);
            if (policy.getMysqlTrustStore() != null) {
                // 固定部署信任库后，拒绝回退到其他受信任证书。
                properties.setProperty(
                        "trustCertificateKeyStoreUrl",
                        Path.of(policy.getMysqlTrustStore()).toUri().toString());
                properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
                properties.setProperty("trustCertificateKeyStorePassword", policy.getMysqlTrustStorePassword());
                properties.setProperty("fallbackToSystemTrustStore", "false");
            }
        } else {
            // PostgreSQL 的 server-side read-only 与证书身份检查均覆盖自动提交查询。
            protocol = "jdbc:postgresql://";
            properties.setProperty("sslmode", "verify-full");
            properties.setProperty("gssEncMode", "disable");
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", Integer.toString(source.queryTimeoutSeconds()));
            properties.setProperty("readOnlyMode", "always");
            properties.setProperty("ApplicationName", marker);
            properties.setProperty(
                    "options",
                    "-c statement_timeout=" + source.queryTimeoutSeconds() * 1000
                            + " -c search_path=pg_catalog -c TimeZone=UTC");
            if (policy.getPostgresRootCert() != null) {
                // 使用部署提供的 PostgreSQL CA 文件。
                properties.setProperty("sslrootcert", policy.getPostgresRootCert());
            }
        }

        String database =
                URLEncoder.encode(source.database(), StandardCharsets.UTF_8).replace("+", "%20");
        Connection connection =
                DriverManager.getConnection(protocol + host + ":" + source.port() + "/" + database, properties);
        try {
            // 用驱动的服务端只读模式防止意外修改源库。
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException exception) {
            // 初始化不完整的连接只能关闭，且保留最初出错的原因。
            try (connection) {
                throw exception;
            }
        }
    }

    /**
     * 解析发生在准入检查之前，实际 JDBC 目标固定为已核验的 IP，避免驱动再次解析。
     */
    private String verifiedAddress(SourceConnection source) throws SQLException {
        if (!source.host().matches("[a-zA-Z0-9_.:-]{1,253}") || source.port() < 1 || source.port() > 65535) {
            // 仅接受主机或 IP 地址，拒绝 JDBC 参数、套接字及备用主机注入。
            throw new SQLException("连接地址或端口格式无效", "DO001");
        }

        InetAddress[] addresses;
        try {
            // 重新测试和每次实际连接均执行相同的解析与检查。
            addresses = InetAddress.getAllByName(source.host());
        } catch (UnknownHostException exception) {
            // 域名不能解析为部署允许的目标时停止探测。
            throw new SQLException("无法解析连接地址", "DO002", exception);
        }

        for (InetAddress address : addresses) {
            // 拒绝包含任一未经核验地址的解析结果。
            if (!policy.getAllowedEndpoints().contains(endpointHost(address) + ":" + source.port())) {
                throw new SQLException("连接目标未获平台批准", "DO001");
            }
        }
        return endpointHost(addresses[0]);
    }

    /**
     * 格式化部署策略和 JDBC URL 共用的端点地址。
     */
    private String endpointHost(InetAddress address) {
        String host = address.getHostAddress();
        return host.contains(":") ? "[" + host + "]" : host;
    }

    /**
     * 核验是否可作为专用只读账号使用。检查不进行试写或读取业务行。
     */
    public boolean readOnlySafety(Connection connection, SourceConnection source) throws SQLException {
        if (source.type() == Type.MYSQL) {
            // SHOW GRANTS 暴露角色、授权能力和实际权限，未知组合保守拒绝。
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SHOW GRANTS")) {
                boolean found = false;
                while (rows.next()) {
                    // 禁止赋权选项和未知授权。读权限或 USAGE 可继续核验目录能力。
                    found = true;
                    String grant = rows.getString(1);
                    if (!MYSQL_READ_GRANT.matcher(grant).matches()
                            || grant.toUpperCase(java.util.Locale.ROOT).contains("WITH GRANT OPTION")) {
                        return false;
                    }
                }
                return found;
            }
        }

        // 检查集群角色、数据库和 schema 创建权、现有对象写入权及用户自定义提权函数。
        String sql =
                """
                SELECT NOT (r.rolsuper OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication OR r.rolbypassrls)
                   AND NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member = r.oid)
                   AND NOT has_database_privilege(current_database(), 'CREATE,TEMP')
                   AND NOT EXISTS (SELECT 1 FROM pg_namespace n WHERE has_schema_privilege(n.oid, 'CREATE'))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND (
                         (c.relkind IN ('r', 'p', 'v', 'm', 'f') AND (
                            c.relowner = r.oid OR has_table_privilege(c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                            OR has_any_column_privilege(c.oid, 'INSERT,UPDATE,REFERENCES')))
                         OR (c.relkind = 'S' AND (c.relowner = r.oid OR has_sequence_privilege(c.oid, 'USAGE,UPDATE')))))
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                       WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.prosecdef
                         AND has_schema_privilege(n.oid, 'USAGE') AND has_function_privilege(p.oid, 'EXECUTE'))
                FROM pg_roles r WHERE r.rolname = current_user
                """;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            // 无法取得账号结论时保持未通过。
            return rows.next() && rows.getBoolean(1);
        }
    }

    /**
     * 获取 PostgreSQL 账号可使用的 Schema，不扩展到当前数据库之外。
     */
    public List<String> visibleSchemas(Connection connection, SourceConnection source) throws SQLException {
        if (source.type() == Type.MYSQL) {
            // MySQL 使用数据库作为范围，没有单独的 Schema 输入。
            return List.of();
        }

        String sql =
                """
                SELECT nspname FROM pg_namespace
                WHERE has_schema_privilege(oid, 'USAGE') AND NOT pg_is_other_temp_schema(oid)
                  AND nspname NOT LIKE 'pg_toast%'
                ORDER BY nspname
                """;
        List<String> schemas = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                // 返回来源名称，保留大小写和其他合法字符。
                schemas.add(rows.getString(1));
            }
        }
        return List.copyOf(schemas);
    }

    /**
     * 枚举固定范围内的对象，按库原生标识保留分区和视图类型。
     */
    public List<ObjectRef> enumerate(Connection connection, SourceConnection source) throws SQLException {
        List<ObjectRef> objects = new ArrayList<>();
        if (source.type() == Type.MYSQL) {
            // MySQL 目录没有跨库发现，使用等值绑定保留数据库名称字面含义。
            String sql =
                    """
                    SELECT t.TABLE_NAME, t.TABLE_TYPE, t.TABLE_COMMENT FROM information_schema.TABLES t
                    WHERE BINARY t.TABLE_SCHEMA = BINARY DATABASE() ORDER BY t.TABLE_NAME
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(source.queryTimeoutSeconds());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        // MySQL 缺少普通表稳定身份依据，目录对象身份由后续核实维护。
                        String type =
                                switch (rows.getString(2)) {
                                    case "BASE TABLE" -> "TABLE";
                                    case "SYSTEM VIEW" -> "SYSTEM";
                                    case "VIEW" -> "VIEW";
                                    default -> rows.getString(2);
                                };
                        objects.add(new ObjectRef(
                                source.database(), "", rows.getString(1), type, rows.getString(3), null, null));
                    }
                }
            }
            return List.copyOf(objects);
        }

        // 同名对象通过 Schema 与名称共同定位。保留子分区的数据库声明身份。
        String sql =
                """
                SELECT n.nspname, c.relname, c.relkind, obj_description(c.oid, 'pg_class'), c.oid,
                       c.relispartition, c.relpersistence,
                       (SELECT pn.nspname || '.' || parent.relname FROM pg_inherits i
                        JOIN pg_class parent ON parent.oid = i.inhparent
                        JOIN pg_namespace pn ON pn.oid = parent.relnamespace
                        WHERE i.inhrelid = c.oid ORDER BY i.inhseqno LIMIT 1)
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND has_schema_privilege(n.oid, 'USAGE')
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND NOT pg_is_other_temp_schema(n.oid)
                ORDER BY c.relname
                """;
        List<String> available = visibleSchemas(connection, source);
        if (!available.containsAll(source.schemas())) {
            // 范围失权不能借空结果成功撤下已有目录。
            throw new SQLException("配置的 Schema 已不可访问", "42501");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(source.queryTimeoutSeconds());
            for (String schema : source.schemas()) {
                // 每个 Schema 单独限定查询，避免跨出设置范围。
                statement.setString(1, schema);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        // PostgreSQL 的数据库对象 ID 给后续事实核实提供证据。
                        objects.add(new ObjectRef(
                                source.database(),
                                rows.getString(1),
                                rows.getString(2),
                                postgresType(rows),
                                rows.getString(4),
                                rows.getString(5),
                                rows.getString(8)));
                    }
                }
            }
        }
        return List.copyOf(objects);
    }

    /**
     * 区分 PostgreSQL 的实际对象类别，分区父子关系单独读取。
     */
    private String postgresType(ResultSet row) throws SQLException {
        if (row.getBoolean(6) || "p".equals(row.getString(3))) {
            // 分区表保留父对象路径，分区根没有父对象。
            return "PARTITION";
        }
        if ("t".equals(row.getString(7))) {
            // 会话只能发现自身可见的临时对象。
            return "TEMPORARY";
        }
        return switch (row.getString(3)) {
            case "v" -> "VIEW";
            case "m" -> "MATERIALIZED_VIEW";
            case "f" -> "FOREIGN";
            default -> List.of("pg_catalog", "information_schema").contains(row.getString(1)) ? "SYSTEM" : "TABLE";
        };
    }

    /**
     * 读取一个对象的字段、适用的键与索引。缺少核心字段必须保留失败事实。
     */
    public Structure read(Connection connection, SourceConnection source, ObjectRef object) throws SQLException {
        List<Field> fields = new ArrayList<>();
        boolean view = "VIEW".equals(object.type());
        String sql = source.type() == Type.MYSQL
                ? """
                        SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE = 'YES', COLUMN_DEFAULT,
                               EXTRA REGEXP 'auto_increment|GENERATED', COLUMN_COMMENT, ORDINAL_POSITION
                        FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA = BINARY DATABASE()
                          AND BINARY TABLE_NAME = BINARY ? ORDER BY ORDINAL_POSITION
                        """
                : """
                        SELECT a.attname, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                               pg_get_expr(d.adbin, d.adrelid), a.attgenerated <> '' OR a.attidentity <> '',
                               col_description(c.oid, a.attnum), a.attnum
                        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        JOIN pg_attribute a ON a.attrelid = c.oid
                        LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                        WHERE n.nspname = ? AND c.relname = ? AND c.oid::text = ? AND a.attnum > 0 AND NOT a.attisdropped
                        ORDER BY a.attnum
                        """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(source.queryTimeoutSeconds());
            if (source.type() == Type.POSTGRESQL) {
                // 防止结构读取期间的同名重建与原对象混用。
                statement.setString(1, object.schema());
                statement.setString(2, object.name());
                statement.setString(3, object.identity());
            } else {
                // 连接已固定为当前 MySQL 库，仅绑定该库内实际枚举的对象名。
                statement.setString(1, object.name());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // 普通视图的字段不按物理表表达默认值和生成策略。
                    fields.add(new Field(
                            rows.getString(1),
                            rows.getString(2),
                            rows.getBoolean(3),
                            view ? null : rows.getString(4),
                            !view && rows.getBoolean(5),
                            rows.getString(6),
                            rows.getInt(7)));
                }
            }
        }
        if (fields.isEmpty()
                || fields.stream()
                        .anyMatch(field -> field.type() == null || field.type().isBlank())) {
            // 保留实际失败原因，不提交缺少字段结构的目录。
            throw new SQLException("无法读取完整的字段名和字段类型", "DO003");
        }
        if (view) {
            // 普通视图不需要读取物理表索引和键。
            return new Structure(object, List.copyOf(fields), List.of(), List.of(), List.of());
        }

        DatabaseMetaData metadata = connection.getMetaData();
        String schema = object.schema().isEmpty() ? null : object.schema();
        List<Key> primary = new ArrayList<>();
        List<Key> foreign = new ArrayList<>();
        List<Key> indexes = new ArrayList<>();
        try (ResultSet rows = metadata.getPrimaryKeys(object.catalog(), schema, object.name())) {
            while (rows.next()) {
                // 保留复合主键字段次序。
                primary.add(new Key(
                        rows.getString("PK_NAME"),
                        rows.getString("COLUMN_NAME"),
                        rows.getInt("KEY_SEQ"),
                        null,
                        null,
                        true));
            }
        }
        try (ResultSet rows = metadata.getImportedKeys(object.catalog(), schema, object.name())) {
            while (rows.next()) {
                // 外键目标只保存来源声明，供后续业务核实。
                String targetSchema = rows.getString("PKTABLE_SCHEM");
                String targetTable = rows.getString("PKTABLE_NAME");
                String target = targetSchema == null ? targetTable : targetSchema + "." + targetTable;
                foreign.add(new Key(
                        rows.getString("FK_NAME"),
                        rows.getString("FKCOLUMN_NAME"),
                        rows.getInt("KEY_SEQ"),
                        target,
                        rows.getString("PKCOLUMN_NAME"),
                        false));
            }
        }
        try (ResultSet rows = metadata.getIndexInfo(object.catalog(), schema, object.name(), false, true)) {
            while (rows.next()) {
                // JDBC 表统计行没有索引身份，不作为索引成员。
                if (rows.getShort("TYPE") != DatabaseMetaData.tableIndexStatistic) {
                    indexes.add(new Key(
                            rows.getString("INDEX_NAME"),
                            rows.getString("COLUMN_NAME"),
                            rows.getInt("ORDINAL_POSITION"),
                            null,
                            null,
                            !rows.getBoolean("NON_UNIQUE")));
                }
            }
        }
        return new Structure(object, List.copyOf(fields), ordered(primary), ordered(foreign), ordered(indexes));
    }

    /**
     * 规范化 JDBC 的键返回顺序，避免驱动返回次序导致虚假的结构差异。
     */
    private List<Key> ordered(List<Key> keys) {
        return keys.stream()
                .sorted(Comparator.comparing(Key::name, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(Key::ordinal))
                .toList();
    }

    /**
     * 取得连接身份并确认后续可查证的标记存在。调用方必须在实际采集读取前提交登记。
     */
    public Session session(Connection connection, SourceConnection source, String marker) throws SQLException {
        String sql = source.type() == Type.MYSQL
                ? "SELECT @@server_uuid, CONNECTION_ID(), NULL"
                : """
                        SELECT pg_postmaster_start_time()::text || ':' || inet_server_addr()::text || ':' || inet_server_port(),
                               pid, backend_start::text FROM pg_stat_activity WHERE pid = pg_backend_pid()
                        """;
        Session identity;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                // 无法观察当前会话时不具备取消核实能力。
                throw new SQLException("无权确认当前采集会话", "DO004");
            }
            identity = new Session(rows.getString(1), rows.getLong(2), rows.getString(3), marker);
        }
        if (isStopped(connection, source, identity)) {
            // 活的原连接本身必须能查到标记，防止观察权限不足被误认成连接结束。
            throw new SQLException("源库无法观察当前采集连接", "DO004");
        }
        return identity;
    }

    /**
     * 从同一源库的独立连接确认原身份已消失。实例不匹配时不释放占位。
     */
    public boolean isStopped(Connection observer, SourceConnection source, Session session) throws SQLException {
        String sql = source.type() == Type.MYSQL
                ? """
                        SELECT @@server_uuid,
                               EXISTS(SELECT 1 FROM performance_schema.session_account_connect_attrs
                                      WHERE PROCESSLIST_ID = ? AND ATTR_NAME = 'dataocean_marker' AND ATTR_VALUE = ?),
                               EXISTS(SELECT 1 FROM performance_schema.session_account_connect_attrs
                                      WHERE PROCESSLIST_ID=CONNECTION_ID() AND ATTR_NAME='dataocean_marker')
                        """
                : """
                        SELECT pg_postmaster_start_time()::text || ':' || inet_server_addr()::text || ':' || inet_server_port(),
                               EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid = ? AND application_name = ?
                                      AND backend_start::text = ? AND usename = current_user AND datname = current_database()),
                               EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=pg_backend_pid() AND application_name LIKE 'do-%')
                        """;
        try (PreparedStatement statement = observer.prepareStatement(sql)) {
            statement.setQueryTimeout(source.queryTimeoutSeconds());
            statement.setLong(1, session.sessionId());
            statement.setString(2, session.marker());
            if (source.type() == Type.POSTGRESQL) {
                // PostgreSQL PID 复用时还须匹配原启动时刻。
                statement.setString(3, session.started());
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !session.instance().equals(rows.getString(1))) {
                    // 目标切换或库重启意味着当前观察不足以认定原实例的运行结果。
                    throw new SQLException("无法核对原采集实例", "DO004");
                }
                if (!rows.getBoolean(3)) {
                    // 观察账号无法读取自身标记时，查询为空不能作为原会话消失证据。
                    throw new SQLException("当前连接无法读取可靠的源会话标记", "DO004");
                }
                return !rows.getBoolean(2);
            }
        }
    }
}
