package com.dataocean.module.datasource.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 在单独创建的平台验收库验证 Flyway 基线和数据源模块约束。
 */
@EnabledIfEnvironmentVariable(named = "DO_IT_SOURCE_ENABLED", matches = "true")
class DatasourceMigrationIT {

    /**
     * 使用仅拥有目标测试库权限的账号验证实际表与 Flyway 版本。
     */
    @Test
    void appliesToIsolatedPlatformSchema() throws Exception {
        String db = "do_platform_acceptance_20261118";
        String url = "jdbc:mysql://127.0.0.1:3307/" + db + "?sslMode=REQUIRED";
        String account = "do_platform_it_20261118";
        String password = System.getenv("DO_IT_PLATFORM_PASSWORD");
        try (var connection = DriverManager.getConnection(url, account, password);
                var statement = connection.createStatement();
                var database = statement.executeQuery("SELECT DATABASE()")) {
            // 重核当前库身份，只允许这个合成平台测试库接受迁移。
            assertThat(database.next()).isTrue();
            assertThat(database.getString(1)).isEqualTo(db);
        }

        Flyway flyway = Flyway.configure().dataSource(url, account, password).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");

        String sql =
                """
                SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN
                    ('ds_source','ds_connection_revision','ds_collection_task','ds_collection_execution','ds_snapshot',
                     'ds_object_revision','ds_change','ds_connection_change')
                """;
        try (var connection = DriverManager.getConnection(url, account, password);
                var statement = connection.createStatement();
                var tables = statement.executeQuery(sql)) {
            // 最终状态必须存在于真实 MySQL 数据字典。
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isEqualTo(8);
        }
    }
}
