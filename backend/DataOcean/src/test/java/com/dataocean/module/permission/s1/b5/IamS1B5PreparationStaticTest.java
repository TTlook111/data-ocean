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
 * IAM 迁移与恢复边界的静态仓库检查。只读文件系统，不连接真实数据库，不执行 Flyway 或 bootstrap。
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
    void currentDocumentationPreservesMigrationAndRecoveryBoundaries() throws IOException {
        String guide = read(Path.of("..", "..", "docs/development/本地开发与验证.md"));
        assertThat(guide).contains("## 跨环境变更与恢复边界", "V53 编号永久不再使用", "真实数据库 Flyway 版本");
        assertThat(guide).contains("批准的构建标识", "维护窗口", "真实的正整数 userId", "^[1-9]\\d*$");
        assertThat(guide).contains("停止应用写入", "不擅自停止或重建共享基础设施");
        assertThat(guide).contains("--result-file=", "禁止 PowerShell 重定向或管道改写编码");
        assertThat(guide).contains("--routines", "--triggers", "--events", "DEFINER", "--single-transaction");
        assertThat(guide).contains("--quick", "--hex-blob", "--set-gtid-purged=OFF", "--no-tablespaces");
        assertThat(guide).contains("退出码", "文件存在与合理大小", "Dump completed", "SHA256", "对象清单");
        assertThat(guide).contains("任一失败即停止", "禁止覆盖式导入非空目标库", "删除并重建目标库必须另获明确授权");
        assertThat(guide).contains("隔离恢复演练必须使用独立 MySQL 实例", "仅改 schema 不算隔离", "@@server_uuid");
        assertThat(guide).contains("localhost", "127.0.0.1", "::1", "event_scheduler=OFF", "凭据隔离");
        assertThat(guide).contains("清理也仅限独立确认的演练实例和库", "SOURCE", "--default-character-set=utf8mb4", "--binary-mode=1");
        assertThat(guide).contains("已产生新 S1", "不能简单删除", "禁止新旧权限双读兜底");
        assertThat(guide).doesNotContain("Flyway 最大成功版本必须为 V50", "`query_task.suggested_questions` 必须不存在");
        // 一次性迁移入口已退休；现行入口继续检查版本、隔离范围、只读授权和不完整批次。
        String preflight = read(Path.of("..", "..", "scripts/system-acceptance/acceptance.py"));
        assertThat(preflight).contains("assert_read_only_grants", "non-isolated", "Database/code migration mismatch");
        assertThat(preflight).contains("INCOMPLETE", "expectedIds", "full_project_acceptance");
        assertThat(preflight).doesNotContain("DROP DATABASE", "TRUNCATE TABLE", "docker run");
        String iam = read(Path.of("..", "..", "docs/modules/007-iam-s1.md"));
        assertThat(iam).contains("## 旧权限退役与保留边界", "sys_role_permission", "专用 Controller、Mapper、Resolver 已退役");
        assertThat(iam).contains("不从旧授权映射或回填新授权", "禁止新旧权限双读兜底");
        assertThat(iam).contains("保留账号及密码安全", "真实部门", "元数据与知识版本/chunk/Milvus", "会话/查询结果");
        assertThat(iam).contains("全部已执行的 Flyway migration 和 schema history", "permission_change_log", "access_approval_request");
        assertThat(iam).contains("只读历史", "JWT 黑名单", "tokenVersion", "替代机制未实现并验收前不得删除");
        String followUp = read(Path.of("..", "..", "docs/development/后续开发.md"));
        String instructions = read(Path.of("..", "..", "AGENTS.md"));
        assertThat(instructions).contains("V53 is permanently unused");
        assertThat(instructions).contains("Historical documents and specs do not authorize");
        // 现行文档跟随真实迁移目录，不再要求保留 B5 当时的版本上限。
        try (Stream<Path> migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            int highestVersion = migrations
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V\\d+__.*\\.sql"))
                    .mapToInt(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                    .max()
                    .orElseThrow();
            String latestMigration = "V" + highestVersion;
            assertThat(followUp).contains(latestMigration);
            assertThat(read(Path.of("..", "..", "docs/项目现状.md"))).contains(latestMigration);
        }
        assertThat(followUp).doesNotContain("B5_EXPECTED_SHA");
        assertThat(guide).contains("独立 MySQL 实例");
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
