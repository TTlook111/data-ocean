package com.dataocean.module.datasource.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Session;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 两种真实业务源验收样例，使用受控证书、专用只读账号和真实源端会话。
 */
@EnabledIfEnvironmentVariable(named = "DO_IT_SOURCE_ENABLED", matches = "true")
class SourceJdbcIT {

    /**
     * 核对两类数据库结构事实、普通视图的属性边界和会话停止证据。
     */
    @ParameterizedTest
    @EnumSource(Type.class)
    void readsStructureAndProvesOriginalConnectionClosed(Type type) throws Exception {
        SourceConnection source = fixture(type);
        SourceJdbc jdbc = new SourceJdbc(policy(source));
        String password = System.getenv(type == Type.MYSQL ? "DO_IT_MYSQL_PASSWORD" : "DO_IT_PG_PASSWORD");
        String marker = "do-it-" + UUID.randomUUID();
        Session original;

        try (Connection observer = jdbc.open(source, password, "do-it-observer")) {
            try (Connection reader = jdbc.open(source, password, marker)) {
                // 正式连接策略与真实源库权限共同生效。
                assertThat(jdbc.readOnlySafety(reader, source)).isTrue();
                original = jdbc.session(reader, source, marker);
                assertThat(jdbc.isStopped(observer, source, original)).isFalse();

                List<ObjectRef> objects = jdbc.enumerate(reader, source);
                ObjectRef orderParent = objects.stream()
                        .filter(object -> "parent".equals(object.name()) && !"finance".equals(object.schema()))
                        .findFirst()
                        .orElseThrow();
                Structure parent = jdbc.read(reader, source, orderParent);
                // 后续采集验收会追加合成字段，基础字段及其顺序仍须准确。
                assertThat(parent.fields())
                        .extracting(SourceMetadata.Field::name)
                        .startsWith("id", "name");
                assertThat(parent.fields().get(1).comment()).isEqualTo("示例名称");
                assertThat(parent.primaryKeys())
                        .extracting(SourceMetadata.Key::field)
                        .containsExactly("id");

                ObjectRef child = objects.stream()
                        .filter(object -> "child".equals(object.name()))
                        .findFirst()
                        .orElseThrow();
                Structure childStructure = jdbc.read(reader, source, child);
                assertThat(childStructure.foreignKeys())
                        .extracting(SourceMetadata.Key::field)
                        .containsExactly("parent_id");
                assertThat(childStructure.indexes())
                        .extracting(SourceMetadata.Key::field)
                        .contains("parent_id");

                ObjectRef view = objects.stream()
                        .filter(object -> "sample_view".equals(object.name()))
                        .findFirst()
                        .orElseThrow();
                assertThat(jdbc.read(reader, source, view).indexes()).isEmpty();

                if (type == Type.POSTGRESQL) {
                    // 同名表和分区须保留实际的 Schema 与类型。
                    assertThat(objects.stream()
                                    .filter(object -> "parent".equals(object.name()))
                                    .toList())
                            .extracting(ObjectRef::schema)
                            .containsExactly("sales", "finance");
                    assertThat(objects).anyMatch(object -> "MATERIALIZED_VIEW".equals(object.type()));
                    assertThat(objects).anyMatch(object -> "sales.partitioned".equals(object.parent()));
                }

                // 单条查询结束后会话仍存在，不能据此释放同源占位。
                cancelStatement(reader, type);
                assertThat(jdbc.isStopped(observer, source, original)).isFalse();
            }

            // 只有独立连接确认原标记消失，才认为旧连接不能继续读取。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!jdbc.isStopped(observer, source, original) && System.nanoTime() < deadline) {
                // 等待源端完成连接关闭后的清理。
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertThat(jdbc.isStopped(observer, source, original)).isTrue();
            assertThat(jdbc.isStopped(
                            observer,
                            source,
                            new Session(original.instance(), original.sessionId(), original.started(), "reused-id")))
                    .isTrue();
        }
    }

    /**
     * 只取消当前 JDBC 语句，并等待服务端给出结果或中止状态。
     */
    private void cancelStatement(Connection reader, Type type) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Statement statement = reader.createStatement()) {
            CountDownLatch started = new CountDownLatch(1);
            Future<Boolean> finished = executor.submit(() -> {
                // 已进入 SQL 读取动作，等待另一个线程取消。
                started.countDown();
                try (var ignored =
                        statement.executeQuery(type == Type.MYSQL ? "SELECT SLEEP(8)" : "SELECT pg_sleep(8)")) {
                    // MySQL 可通过值 1 报告 SLEEP 被中止。
                    return ignored.next() && type == Type.MYSQL && ignored.getInt(1) == 1;
                } catch (SQLException exception) {
                    // PostgreSQL 或驱动以异常报告语句已取消。
                    return "57014".equals(exception.getSQLState()) || "70100".equals(exception.getSQLState());
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            TimeUnit.MILLISECONDS.sleep(250);
            statement.cancel();
            assertThat(finished.get(4, TimeUnit.SECONDS)).isTrue();
        } finally {
            // 无论驱动是否按预期取消，释放测试线程。
            executor.shutdownNow();
        }
    }

    /**
     * 构造独立验收数据源，MySQL 不需要选择 Schema。
     */
    private SourceConnection fixture(Type type) {
        return type == Type.MYSQL
                ? new SourceConnection(
                        type,
                        "127.0.0.1",
                        3307,
                        "do_source_acceptance_20261118",
                        List.of(),
                        "do_source_it_20261118",
                        12)
                : new SourceConnection(
                        type,
                        "127.0.0.1",
                        5433,
                        "do_source_acceptance",
                        List.of("sales", "finance"),
                        "do_source_it",
                        12);
    }

    /**
     * 证书及静态目标准入来自验收机，源码不含账号密码。
     */
    private SourceConnectionProperties policy(SourceConnection source) {
        SourceConnectionProperties properties = new SourceConnectionProperties();
        properties.setAllowedEndpoints(Set.of(source.host() + ":" + source.port()));
        properties.setMysqlTrustStore(
                Path.of(System.getenv("DO_IT_MYSQL_TRUSTSTORE")).toString());
        properties.setMysqlTrustStorePassword(System.getenv("DO_IT_MYSQL_TRUSTSTORE_PASSWORD"));
        properties.setMysqlVerifyCaOnly(true);
        properties.setPostgresRootCert(System.getenv("DO_IT_PG_ROOT_CERT"));
        return properties;
    }
}
