package com.dataocean.common.security;

import com.dataocean.common.health.SystemHealthController;
import com.dataocean.module.audit.controller.AlertController;
import com.dataocean.module.audit.controller.AuditLogController;
import com.dataocean.module.audit.controller.LineageController;
import com.dataocean.module.audit.controller.LineageEdgeController;
import com.dataocean.module.dashboard.controller.DashboardController;
import com.dataocean.module.datasource.controller.DatasourceAdminController;
import com.dataocean.module.fieldtag.controller.FieldAdminController;
import com.dataocean.module.fieldtag.controller.FieldConfidenceController;
import com.dataocean.module.fieldtag.controller.FieldTagController;
import com.dataocean.module.fieldtag.controller.FeedbackReviewController;
import com.dataocean.module.glossary.controller.GlossaryController;
import com.dataocean.module.governance.controller.MetadataGovernanceController;
import com.dataocean.module.knowledge.controller.KnowledgeDocController;
import com.dataocean.module.metadata.controller.MetadataCatalogController;
import com.dataocean.module.metadata.controller.MetadataCollectionController;
import com.dataocean.module.prompt.controller.PromptTemplateController;
import com.dataocean.module.system.controller.AiConfigController;
import com.dataocean.module.system.controller.OperationLogController;
import com.dataocean.module.system.controller.SyncScheduleController;
import com.dataocean.module.user.controller.DepartmentController;
import com.dataocean.module.user.controller.PermissionController;
import com.dataocean.module.user.controller.RoleController;
import com.dataocean.module.user.controller.UserController;
import com.dataocean.module.versioning.controller.SnapshotVersionController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧权限授权表达式的状态快照：断言这些 Controller 的方法级 {@code @PreAuthorize} 都含通配 {@code '*'}。
 * <p>
 * 这是**记录旧状态**的用例，不是保护新体系。B6 批次 2 已删除三个旧权限 Controller
 * （AccessApproval / AccessPolicy / DatasourcePermission），列表随之缩减；
 * 剩下的实质断言只有 RoleController 与 PermissionController，
 * 它们属 B6 批次 3，届时本用例整体删除（见
 * {@code docs/development/轨道B-B6删除清单与执行顺序.md}）。
 * </p>
 */
class WildcardAuthorizationAnnotationTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            SystemHealthController.class, AlertController.class, AuditLogController.class,
            LineageController.class, LineageEdgeController.class, DashboardController.class,
            DatasourceAdminController.class, FieldAdminController.class, FieldConfidenceController.class,
            FieldTagController.class, FeedbackReviewController.class, GlossaryController.class,
            MetadataGovernanceController.class, KnowledgeDocController.class, MetadataCatalogController.class,
            MetadataCollectionController.class, PromptTemplateController.class, AiConfigController.class,
            OperationLogController.class, SyncScheduleController.class, DepartmentController.class,
            PermissionController.class, RoleController.class, UserController.class, SnapshotVersionController.class);

    @Test
    void everyCurrentProtectedControllerExpressionAcceptsTheRealWildcardAuthority() {
        for (Class<?> controller : CONTROLLERS) {
            assertWildcard(controller, controller.getAnnotation(PreAuthorize.class));
            Arrays.stream(controller.getDeclaredMethods())
                    .map(method -> method.getAnnotation(PreAuthorize.class))
                    .forEach(annotation -> assertWildcard(controller, annotation));
        }
    }

    private static void assertWildcard(Class<?> controller, PreAuthorize annotation) {
        if (annotation != null) {
            assertThat(annotation.value())
                    .as("授权表达式 %s", controller.getSimpleName())
                    .contains("'*'");
        }
    }
}
