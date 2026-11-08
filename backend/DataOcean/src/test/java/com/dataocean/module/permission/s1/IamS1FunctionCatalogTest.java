package com.dataocean.module.permission.s1;

import com.dataocean.module.permission.s1.catalog.IamS1FunctionCatalog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IamS1FunctionCatalogTest {

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
                "benmanes.caffeine", "ConcurrentHashMap");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
