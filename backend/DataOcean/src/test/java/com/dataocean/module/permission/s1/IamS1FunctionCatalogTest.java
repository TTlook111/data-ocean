package com.dataocean.module.permission.s1;

import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class IamS1FunctionCatalogTest {

    private static final Pattern MIGRATION_CODE = Pattern.compile("^\\s*\\(\\d+, '([^']+)'", Pattern.MULTILINE);
    private static final Pattern DESIGN_CODE = Pattern.compile("`([a-zA-Z0-9:_-]+)`");

    @Test
    void migrationContainsExactlyThe54UniqueDesignCodes() throws IOException {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V54__iam_s1_configuration_base.sql"),
                StandardCharsets.UTF_8);
        int functionStart = migration.indexOf("INSERT INTO iam_s1_function");
        int functionEnd = migration.indexOf("ON DUPLICATE KEY UPDATE", functionStart);
        String functionSection = migration.substring(functionStart, functionEnd);
        Set<String> migrationCodes = findAll(MIGRATION_CODE, functionSection);
        Set<String> catalogCodes = IamS1FunctionCatalog.definitions().stream()
                .map(IamS1FunctionCatalog.Definition::code)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        assertThat(migrationCodes).hasSize(54);
        assertThat(catalogCodes).hasSize(54);
        assertThat(migrationCodes).containsExactlyInAnyOrderElementsOf(catalogCodes);
        assertThat(findAll(DESIGN_CODE, readDesignSection())).containsExactlyInAnyOrderElementsOf(catalogCodes);
    }

    @Test
    void dependenciesExpandOnTheJavaSideFromTheFixedCatalog() {
        assertThat(IamS1FunctionCatalog.expand(List.of("query:sql:view")))
                .containsExactly("query:use", "query:sql:view");
        assertThat(IamS1FunctionCatalog.expand(List.of("query:export")))
                .containsExactly("query:use", "query:export");
        assertThat(IamS1FunctionCatalog.expand(List.of("governance:issue:manage")))
                .containsExactly("governance:issue:view", "governance:issue:manage");
        assertThat(IamS1FunctionCatalog.expand(List.of("metadata:release:review")))
                .containsExactly("metadata:release:view", "metadata:release:review");
        assertThat(IamS1FunctionCatalog.expand(List.of("metadata:release:publish")))
                .containsExactly("metadata:release:view", "metadata:release:publish");
        assertThat(IamS1FunctionCatalog.expand(List.of("metadata:release:review")))
                .doesNotContain("metadata:release:publish");
        assertThat(IamS1FunctionCatalog.expand(List.of("metadata:release:publish")))
                .doesNotContain("metadata:release:review");
    }

    @Test
    void migrationHasNoForeignKeysOrBusinessGrantTables() throws IOException {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V54__iam_s1_configuration_base.sql"),
                StandardCharsets.UTF_8).toLowerCase();
        assertThat(migration).doesNotContain("foreign key", "iam_s1_data_grant", "iam_s1_row_condition");
        assertThat(migration).contains(
                "iam_s1_function", "iam_s1_role", "iam_s1_role_function", "iam_s1_user_role",
                "iam_s1_role_datasource", "iam_s1_permission_revision", "iam_s1_audit_event",
                "iam_s1_bootstrap_state");
    }

    @Test
    void s1ProductionPackageHasNoLegacyPermissionMapperOrCacheInput() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/dataocean/module/permission/s1");
        String source = Files.walk(sourceRoot)
                .filter(path -> path.toString().endsWith(".java"))
                .map(this::read)
                .reduce("", String::concat);
        assertThat(source).doesNotContain(
                "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
                "datasource_access", "datasource_access_policy", "DatasourceAccessService",
                "DatasourcePermissionService", "PermissionCalculator", "Caffeine",
                "benmanes.caffeine", "RedisTemplate", "ConcurrentHashMap");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String readDesignSection() throws IOException {
        Path design = Path.of("..", "..", "docs/development/guides/DataOcean-完整权限体系设计.md");
        String content = Files.readString(design, StandardCharsets.UTF_8);
        int start = content.indexOf("## 4. 角色功能目录");
        int end = content.indexOf("## 5. 用户和角色管理", start);
        return content.substring(start, end);
    }

    private Set<String> findAll(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        Set<String> values = new HashSet<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
