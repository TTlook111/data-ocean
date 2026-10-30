package com.dataocean.module.knowledge.support;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LineageDependenciesRegressionTest {
    @Test
    void sharedGenerationParserAndValidatorKeepTableAndColumnIdentity() {
        assertThat(LineageDependencies.parse("ds.db.orders", "ds.db.rollups"))
                .containsExactlyInAnyOrder("table:orders", "table:rollups");
        assertThat(LineageDependencies.parse("ds.db.orders.amount", "ds.db.rollups.total"))
                .containsExactlyInAnyOrder("table:orders", "column:orders.amount", "table:rollups", "column:rollups.total");
        for (Object target : List.of(mock(KnowledgeSnapshotFactValidator.class, CALLS_REAL_METHODS))) {
            List<String> tables = ReflectionTestUtils.invokeMethod(target, "lineageDependencies", "ds.db.orders", "ds.db.rollups");
            assertThat(tables).containsExactlyInAnyOrder("table:orders", "table:rollups");
            List<String> columns = ReflectionTestUtils.invokeMethod(target, "lineageDependencies", "ds.db.orders.amount", "ds.db.rollups.total");
            assertThat(columns).containsExactlyInAnyOrder("table:orders", "column:orders.amount", "table:rollups", "column:rollups.total");
        }
    }
}
