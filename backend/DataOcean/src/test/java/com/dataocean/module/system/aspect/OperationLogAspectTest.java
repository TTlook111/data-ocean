package com.dataocean.module.system.aspect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作日志切面辅助逻辑单测：targetId 提取、资源推断、操作类型推断。
 */
class OperationLogAspectTest {

    @Test
    void extractTargetIdPicksFirstNumericSegment() {
        assertThat(OperationLogAspect.extractTargetId("/api/admin/datasources/1")).isEqualTo("1");
        assertThat(OperationLogAspect.extractTargetId("/api/admin/governance/issues/5/resolve")).isEqualTo("5");
        assertThat(OperationLogAspect.extractTargetId("/api/admin/snapshots/12/audit-logs")).isEqualTo("12");
    }

    @Test
    void extractTargetIdReturnsNullWhenNoNumericSegment() {
        assertThat(OperationLogAspect.extractTargetId("/api/admin/catalog/search")).isNull();
        assertThat(OperationLogAspect.extractTargetId("/api/admin/operation-logs")).isNull();
    }

    @Test
    void inferResourceUsesFirstPathSegment() {
        assertThat(OperationLogAspect.inferResource("/api/admin/datasources/1")).isEqualTo("datasources");
        assertThat(OperationLogAspect.inferResource("/api/admin/metadata/sync")).isEqualTo("metadata");
        assertThat(OperationLogAspect.inferResource("/api/admin/catalog/search")).isEqualTo("catalog");
    }

    @Test
    void inferOperationTypeMapsHttpMethod() {
        assertThat(OperationLogAspect.inferOperationType("POST")).isEqualTo("CREATE");
        assertThat(OperationLogAspect.inferOperationType("PUT")).isEqualTo("UPDATE");
        assertThat(OperationLogAspect.inferOperationType("PATCH")).isEqualTo("UPDATE");
        assertThat(OperationLogAspect.inferOperationType("DELETE")).isEqualTo("DELETE");
        assertThat(OperationLogAspect.inferOperationType("GET")).isEqualTo("QUERY");
    }
}
