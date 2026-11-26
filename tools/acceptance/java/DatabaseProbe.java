import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Update;
import tools.jackson.databind.json.JsonMapper;

/**
 * 对已核实的新平台开发库验证约束、失败回滚和事件租约，连接秘密不进入输出。
 */
public class DatabaseProbe {

    /**
     * SQL 通过标准输入传递，验收报告只保存计数、状态和受控 SQL 错误分类。
     */
    public static void main(String[] args) throws Exception {
        var configuration = VerificationEnvironment.read(Path.of(args[0]));

        String url = configuration.getProperty("spring.datasource.url");

        var output = new LinkedHashMap<String, Object>();
        try (var connection = DriverManager.getConnection(url, configuration.getProperty("spring.datasource.username"), configuration.getProperty("spring.datasource.password"))) {
            VerificationEnvironment.verify(connection, url);
            output.put("schema", connection.getCatalog());
            output.put("version", connection.getMetaData().getDatabaseProductVersion());

            if (args.length > 1 && args[1].equals("hold-role-grant")) {
                // 双 HTTP 事务的并发验收需要暂时锁住要收回的一条授予记录。
                holdRoleGrant(connection);
                return;
            }
            String sql = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            if (args.length > 1 && args[1].equals("outbox-delivered")) {
                // 租约验收直接执行生产 Mapper 注解中的 SQL，不复制另一份条件造成假通过。
                var json = JsonMapper.builder().build();

                var input = json.readTree(sql);

                var mapper = Class.forName("com.dataocean.module.iam.mapper.OutboxMapper");

                String source = String.join(" ", mapper.getMethod("delivered", String.class, String.class).getAnnotation(Update.class).value());

                var names = new ArrayList<String>();

                var parameters = Pattern.compile("#\\{([a-zA-Z]+)}").matcher(source);
                while (parameters.find()) {
                    names.add(parameters.group(1));
                }
                try (var statement = connection.prepareStatement(parameters.replaceAll("?"))) {
                    for (int index = 0; index < names.size(); index++) {
                        statement.setString(index + 1, input.path(names.get(index)).asString());
                    }
                    output.put("updated", statement.executeUpdate());
                }
                System.out.println(json.writeValueAsString(output));
                return;
            }
            try (var statement = connection.createStatement()) {
                if (statement.execute(sql)) {
                    // 调用者只查询本次合成记录的计数和状态，禁止请求凭据列。
                    var rows = new ArrayList<ArrayList<Object>>();
                    try (var result = statement.getResultSet()) {
                        while (result.next()) {
                            var row = new ArrayList<Object>();
                            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                                row.add(result.getObject(column));
                            }
                            rows.add(row);
                        }
                    }
                    output.put("rows", rows);
                } else {
                    output.put("updated", statement.getUpdateCount());
                }
            }
        } catch (SQLException error) {
            // 约束失败不回显 SQL 参数、密码、散列或完整异常消息。
            output.put("sqlState", error.getSQLState());
            output.put("vendorCode", error.getErrorCode());
        }
        System.out.println(JsonMapper.builder().build().writeValueAsString(output));
    }

    /**
     * 锁定调用方本轮创建的一条角色授予，等待关闭输入后回滚只持锁的事务。
     */
    private static void holdRoleGrant(Connection connection) throws Exception {
        var json = JsonMapper.builder().build();
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var fixture = json.readTree(input.readLine());
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("""
                SELECT gp.role_id FROM iam_role_grant_permission gp
                WHERE gp.role_id=? AND gp.permission_code=?
                  AND EXISTS (SELECT 1 FROM iam_role r WHERE r.id=gp.role_id AND r.name=?)
                FOR UPDATE
                """)) {
            // 同时匹配角色 ID、权限编码和本轮角色名称，按主键锁住待收回的授予。
            statement.setLong(1, Long.parseLong(fixture.path("id").asString()));
            statement.setString(2, fixture.path("permission").asString());
            statement.setString(3, fixture.path("name").asString());
            statement.setQueryTimeout(5);
            try (var result = statement.executeQuery()) {
                // 缺少测试角色时中止，不能误报已持锁。
                if (!result.next()) {
                    throw new IllegalStateException("未找到本轮角色授予记录");
                }
            }
            System.out.println(json.writeValueAsString(Map.of("locked", true)));
            System.out.flush();
            input.readLine();
        } finally {
            // 持锁探针不提交任何业务变更。
            connection.rollback();
        }
    }
}
