package com.dataocean.module.permission.s1.b5;

import com.dataocean.module.permission.s1.aspect.IamS1AuthorizationAspect;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态核对 IAM 权限来源和代理拦截。
 */
class IamS1B5PreparationStaticTest {

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
