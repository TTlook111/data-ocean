package com.dataocean.module.permission.s1.b5;

import com.dataocean.module.permission.s1.aspect.IamS1AuthorizationAspect;
import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B5 切换前的静态仓库检查。只读文件系统，不连接真实数据库，不执行 Flyway 或 bootstrap。
 */
class IamS1B5PreparationStaticTest {

    private static final Pattern MIGRATION_CODE = Pattern.compile("^\\s*\\(\\d+, '([^']+)'", Pattern.MULTILINE);
    private static final List<String> REQUIRED_MIGRATIONS = List.of(
            "V51__knowledge_version_review_status_backfill.sql",
            "V52__add_query_suggested_questions.sql",
            "V54__iam_s1_configuration_base.sql",
            "V55__iam_s1_data_authorization.sql",
            "V56__iam_s1_query_execution_evidence.sql",
            "V57__iam_s1_access_request.sql"
    );
    private static final List<String> MIGRATED_CONTROLLERS = List.of(
            "DashboardController",
            "DatasourceAdminController",
            "MetadataCatalogController",
            "MetadataCollectionController",
            "SnapshotVersionController",
            "MetadataGovernanceController",
            "GlossaryController",
            "KnowledgeDocController",
            "PromptTemplateController",
            "UserController",
            "DepartmentController",
            "AuditLogController",
            "LineageController",
            "LineageEdgeController",
            "AlertController",
            "SystemHealthController",
            "OperationLogController",
            "AiConfigController",
            "SyncScheduleController",
            "FieldAdminController",
            "FieldTagController",
            "FieldConfidenceController",
            "FeedbackReviewController"
    );
    private static final List<String> LEGACY_BACKFILL = List.of(
            "sys_role_permission",
            "datasource_access_policy",
            "INSERT INTO sys_role",
            "INSERT INTO sys_permission",
            "INSERT INTO sys_user_role",
            "INSERT INTO datasource_access",
            "INSERT INTO access_approval_request"
    );
    private static final List<String> LEGACY_RESOLVER = List.of(
            "sys_role_permission",
            "sys_permission",
            "datasource_access_policy",
            "DatasourceAccessService",
            "PermissionCalculator",
            "hasAnyAuthority",
            "Caffeine"
    );

    @Test
    void requiredMigrationsExistAndV53StaysUnused() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        assertThat(dir).isDirectory();
        for (String name : REQUIRED_MIGRATIONS) {
            assertThat(dir.resolve(name)).as(name).exists();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<String> v53 = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V53"))
                    .toList();
            assertThat(v53).as("V53 永久不再使用").isEmpty();
        }
    }

    @Test
    void s1MigrationsDoNotBackfillLegacyPermissionsOrCreateForeignKeys() throws IOException {
        for (String name : List.of(
                "V54__iam_s1_configuration_base.sql",
                "V55__iam_s1_data_authorization.sql",
                "V56__iam_s1_query_execution_evidence.sql",
                "V57__iam_s1_access_request.sql")) {
            String sql = read(Path.of("src/main/resources/db/migration", name));
            String lower = sql.toLowerCase();
            assertThat(lower).as(name).doesNotContain("foreign key", "references ");
            for (String token : LEGACY_BACKFILL) {
                assertThat(sql).as(name + " " + token).doesNotContain(token);
            }
        }
    }

    @Test
    void v54ContainsExactlyTheFixed54FunctionCodes() throws IOException {
        String migration = read(Path.of("src/main/resources/db/migration/V54__iam_s1_configuration_base.sql"));
        int start = migration.indexOf("INSERT INTO iam_s1_function");
        int end = migration.indexOf("ON DUPLICATE KEY UPDATE", start);
        Set<String> migrationCodes = findAll(MIGRATION_CODE, migration.substring(start, end));
        Set<String> catalogCodes = IamS1FunctionCatalog.definitions().stream()
                .map(IamS1FunctionCatalog.Definition::code)
                .collect(Collectors.toCollection(HashSet::new));
        assertThat(migrationCodes).hasSize(54);
        assertThat(catalogCodes).hasSize(54);
        assertThat(migrationCodes).containsExactlyInAnyOrderElementsOf(catalogCodes);
    }

    @Test
    void s1ResolversDoNotReadLegacyPermissionFacts() throws IOException {
        Path resourceDir = Path.of("src/main/java/com/dataocean/module/permission/s1/resource");
        String source;
        try (Stream<Path> files = Files.walk(resourceDir)) {
            source = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(this::readUnchecked)
                    .collect(Collectors.joining("\n"));
        }
        source += read(Path.of("src/main/java/com/dataocean/module/permission/s1/service/impl/IamS1AuthorizationResolverImpl.java"));
        source += read(Path.of("src/main/java/com/dataocean/module/permission/s1/service/impl/IamS1DataAuthorizationResolverImpl.java"));
        for (String token : LEGACY_RESOLVER) {
            assertThat(source).as(token).doesNotContain(token);
        }
    }

    @Test
    void authorizationAspectAndProxyRegressionTestsRemain() throws IOException {
        assertThat(IamS1AuthorizationAspect.class.isAnnotationPresent(Aspect.class)).isTrue();
        assertThat(IamS1AuthorizationAspect.class.isAnnotationPresent(Component.class)).isTrue();
        String coverage = read(Path.of(
                "src/test/java/com/dataocean/module/permission/s1/coverage/IamS1EndpointCoverageTest.java"));
        assertThat(coverage).contains("migratedControllersAreActuallyProxiedSoTheAnnotationsRun");
        for (String controller : MIGRATED_CONTROLLERS) {
            assertThat(coverage).as(controller).contains("\"" + controller + "\"");
        }
        assertThat(MIGRATED_CONTROLLERS).hasSize(23);
        String proxyTest = read(Path.of(
                "src/test/java/com/dataocean/module/permission/s1/aspect/MetadataGovernanceControllerAuthorizationTest.java"));
        assertThat(proxyTest).contains("AspectJProxyFactory");
    }

    @Test
    void b5HandbookAndB6InventoryExistWithFrozenV53Decision() throws IOException {
        String handbook = read(Path.of("..", "..", "docs/development/guides/DataOcean-IAM-S1-B5切换与回退手册.md"));
        assertThat(handbook).contains("V53 编号永久不再使用", "真实数据库 Flyway 版本", "V50");
        assertThat(handbook).contains("禁止新旧权限双读兜底");
        assertThat(handbook).contains("真实库只读 SQL 门禁");
        assertThat(handbook).contains("Flyway 最大成功版本必须为 V50");
        assertThat(handbook).contains("`query_task.suggested_questions` 必须不存在");
        assertThat(handbook).contains("iam_protocol_version");
        assertThat(handbook).contains("idx_query_task_iam_protocol");
        assertThat(handbook).contains("iam_s1_%");
        assertThat(handbook).contains("--result-file=");
        assertThat(handbook).contains("--events");
        assertThat(handbook).contains("--default-character-set=utf8mb4");
        assertThat(handbook).contains("禁止覆盖式导入");
        assertThat(handbook).contains("隔离恢复演练");
        assertThat(handbook).contains("DRILL_HOST");
        assertThat(handbook).contains("DRILL_PORT");
        assertThat(handbook).contains("event_scheduler");
        assertThat(handbook).contains("@@server_uuid");
        assertThat(handbook).contains("--set-gtid-purged=OFF");
        assertThat(handbook).contains("--hex-blob");
        assertThat(handbook).contains("--quick");
        assertThat(handbook).contains("--no-tablespaces");
        assertThat(handbook).contains("--binary-mode=1");
        assertThat(handbook).contains("backup is incomplete: Dump completed marker missing");
        assertThat(handbook).contains("backup unexpectedly contains iam_s1 tables");
        assertThat(handbook).contains("Length -lt 4");
        assertThat(handbook).contains("restoring events/routines onto the same instance is forbidden");
        assertThat(handbook).contains("B5_EXPECTED_SHA");
        assertThat(handbook).contains("^[1-9]\\d*$");
        assertThat(handbook).doesNotContain(" > \"$env:BACKUP_DIR");
        assertThat(handbook).doesNotContain("--routines --triggers --databases");
        assertThat(handbook).doesNotContain(
                "mysql --host=$env:DB_HOST --port=$env:DB_PORT --user=$env:DB_USERNAME --password --execute=\"CREATE DATABASE $env:DRILL_DB");
        String preflight = read(Path.of("..", "..", "scripts/iam-s1-b5-preflight.ps1"));
        assertThat(preflight).contains("^[1-9]\\d*$");
        assertThat(preflight).contains("(?m)^\\s*@Aspect\\s*$");
        assertThat(preflight).contains("(?m)^\\s*@Component\\s*$");
        assertThat(preflight).contains("B5_EXPECTED_SHA");
        assertThat(preflight).contains("DRILL_HOST");
        assertThat(preflight).contains("SHA256 is computed after dump");
        assertThat(preflight).doesNotContain("-match '@Aspect'");
        assertThat(preflight).doesNotContain("-match '@Component'");
        String b0 = read(Path.of("..", "..", "docs/development/轨道B-B0权限清单与决策冻结.md"));
        assertThat(b0).contains("## 7. B6 删除清单");
        assertThat(b0).contains("DatasourcePermissionController", "sys_role_permission");
        String followUp = read(Path.of("..", "..", "docs/development/后续开发.md"));
        assertThat(followUp).contains("V53 永久不再使用");
        assertThat(followUp).contains("B5_EXPECTED_SHA");
        assertThat(followUp).contains("独立 MySQL 实例");
        assertThat(followUp).doesNotContain("必须在本轮迁移执行前补上");
    }

    private Set<String> findAll(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        Set<String> values = new HashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String readUnchecked(Path path) {
        try {
            return read(path);
        } catch (IOException exception) {
            throw new IllegalStateException(path.toString(), exception);
        }
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
