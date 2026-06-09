package com.dataocean.common.util;

/**
 * JDBC URL 构建工具类。
 * <p>
 * 统一项目中 MySQL JDBC URL 的构建逻辑，避免各处手写 URL 导致参数不一致。
 * </p>
 * <p>
 * 场景区分：
 * <ul>
 *   <li>元数据采集/统计采集：connectTimeout=10s, socketTimeout=60s（允许长时间查询）</li>
 *   <li>连接测试/健康检查：connectTimeout=5s, socketTimeout=5s（快速失败）</li>
 * </ul>
 * </p>
 */
public final class JdbcUrlBuilder {

    /** 元数据采集连接超时（毫秒） */
    private static final int METADATA_CONNECT_TIMEOUT_MS = 10_000;

    /** 元数据采集读取超时（毫秒） */
    private static final int METADATA_SOCKET_TIMEOUT_MS = 60_000;

    /** 连接测试连接超时（毫秒） */
    private static final int CONNECTION_TEST_CONNECT_TIMEOUT_MS = 5_000;

    /** 连接测试读取超时（毫秒） */
    private static final int CONNECTION_TEST_SOCKET_TIMEOUT_MS = 5_000;

    private JdbcUrlBuilder() {
        // 工具类不允许实例化
    }

    /**
     * 构建元数据采集/统计采集用的 MySQL JDBC URL。
     * <p>
     * 超时参数：connectTimeout=10s, socketTimeout=60s，
     * 适用于允许长时间运行的元数据扫描和统计查询。
     * </p>
     *
     * @param host         数据库主机地址
     * @param port         数据库端口
     * @param databaseName 数据库名称
     * @param charset      字符编码（可为 null，null 时使用 utf8）
     * @return 完整的 JDBC URL
     */
    public static String metadataMysqlUrl(String host, Integer port, String databaseName, String charset) {
        String encoding = resolveJdbcEncoding(charset);
        return String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&characterEncoding=%s&connectTimeout=%d&socketTimeout=%d",
                host, port, databaseName, encoding,
                METADATA_CONNECT_TIMEOUT_MS, METADATA_SOCKET_TIMEOUT_MS
        );
    }

    /**
     * 构建连接测试/健康检查用的 MySQL JDBC URL。
     * <p>
     * 超时参数：connectTimeout=5s, socketTimeout=5s（快速失败）。
     * 包含安全参数：allowLoadLocalInfile=false、autoDeserialize=false、allowUrlInLocalInfile=false。
     * </p>
     *
     * @param host         数据库主机地址
     * @param port         数据库端口
     * @param databaseName 数据库名称
     * @param charset      字符编码（可为 null，null 时使用 utf8）
     * @return 完整的 JDBC URL
     */
    public static String connectionTestMysqlUrl(String host, Integer port, String databaseName, String charset) {
        String encoding = resolveJdbcEncoding(charset);
        return String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                        + "&connectTimeout=%d&socketTimeout=%d"
                        + "&characterEncoding=%s"
                        + "&allowLoadLocalInfile=false&autoDeserialize=false&allowUrlInLocalInfile=false",
                host, port, databaseName,
                CONNECTION_TEST_CONNECT_TIMEOUT_MS, CONNECTION_TEST_SOCKET_TIMEOUT_MS,
                encoding
        );
    }

    /**
     * 解析 JDBC 字符编码。
     * <p>
     * MySQL 的 utf8mb4 在 JDBC 中应映射为 utf8，
     * 因为 JDBC 驱动使用 utf8 表示完整的 Unicode（包括 4 字节字符）。
     * </p>
     *
     * @param charset 原始字符编码（可为 null 或空）
     * @return 解析后的字符编码
     */
    public static String resolveJdbcEncoding(String charset) {
        if (charset == null || charset.isBlank()) {
            return "utf8";
        }
        // MySQL 的 utf8mb4 在 JDBC 中应使用 utf8
        if ("utf8mb4".equalsIgnoreCase(charset.trim())) {
            return "utf8";
        }
        return charset.trim();
    }
}
