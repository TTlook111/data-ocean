package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CredentialRow;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.query.service.QueryProtocol.Column;
import com.dataocean.module.query.service.QuerySql.Compiled;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 补齐大量结果、可疑执行账号、计量字段与源端取消失败。
 */
class QueryJdbcTest {
    /** 使用同一来源和可核对的已编译计划。 */
    private final Source source = new Source(71, "工业能耗", "ENABLED", 5L, 1L, 1, false, null, null, null, null);
    private final SourceJdbc sourceJdbc = mock(SourceJdbc.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final Runnable permit = mock(Runnable.class);
    private QueryJdbc query;

    /**
     * 业务 SQL 仍经过实际执行实现，替身只模拟 JDBC 极端场景。
     */
    @BeforeEach
    void jdbc() throws SQLException {
        var sources = mock(DatasourceMapper.class);
        var config = new SourceConfigurationDTO();
        config.setDatabaseType("MYSQL");
        config.setHost("127.0.0.1");
        config.setPort(3306);
        config.setDatabaseName("energy");
        config.setAccount("reader");
        config.setSchemas(List.of());
        var limits = new RuntimePolicyDTO();
        limits.setQueryTimeoutSeconds(10);
        when(sources.revision(71, 5)).thenReturn(new RevisionRow(5, 1, 3L, config, limits, null));
        when(sources.credential(71, 5)).thenReturn(new CredentialRow("fixture", new byte[]{1}));
        when(sourceJdbc.open(any(), any(), eq("do-query-x"))).thenReturn(connection);
        when(connection.prepareStatement("SELECT value FROM energy")).thenReturn(statement);
        when(sourceJdbc.readOnlySafety(eq(connection), any())).thenReturn(true);
        when(statement.executeQuery()).thenReturn(rows);
        query = new QueryJdbc(sources, sourceJdbc, mock(SourceSecretCodec.class));
    }

    /**
     * 上限后的实际行仅用于确认截断。执行取消异常不会伪造已取消。
     */
    @Test
    void truncatesRowsAndReleasesTheCancelledStatement() throws SQLException {
        var n = new AtomicInteger();
        when(rows.next()).thenAnswer(call -> n.getAndIncrement() < 2001);
        when(rows.getString(1)).thenReturn("1");
        doThrow(new SQLException("already idle")).when(statement).cancel();
        var data = query.execute(source, "x", plan(false), () -> 2, () -> query.cancel("x"));
        assertThat(data.rows()).hasSize(2000);
        assertThat(data.truncated()).isTrue();
        query.cancel("x");
        verify(statement).cancel();
        verify(statement).setMaxRows(2001);
        verify(statement).setQueryTimeout(2);
        verify(statement).setObject(1, "zone-a");
        verify(statement).close();
    }

    /**
     * 未满足源端只读门控时连执行许可也不得发放。
     */
    @Test
    void failedReadOnlyProbeStopsBeforeExecution() throws SQLException {
        when(sourceJdbc.readOnlySafety(eq(connection), any())).thenReturn(false);
        assertThatThrownBy(() -> query.execute(source, "x", plan(false), () -> 2, permit))
                .isInstanceOf(QueryFailure.class).hasMessage("PERMISSION_DENIED");
        verify(permit, never()).run();
        verify(statement, never()).executeQuery();
    }

    /**
     * 超大单元和不一致计量列都不能交付。
     */
    @Test
    void rejectsLargeCellsAndMixedUnitsBetweenDays() throws SQLException {
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("x".repeat(201));
        assertThatThrownBy(() -> query.execute(source, "x", plan(false), () -> 2, permit))
                .isInstanceOf(QueryFailure.class).hasMessage("RESOURCE_EXCEEDED");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("4.0");
        when(rows.getString(2)).thenReturn("kWh", "MWh");
        when(rows.getString(3)).thenReturn("kWh", "MWh");
        when(rows.getBoolean(4)).thenReturn(true);
        assertThatThrownBy(() -> query.execute(source, "x", plan(true), () -> 2, permit))
                .isInstanceOf(QueryFailure.class).hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 构造有绑定参数的限量输出，与正式编译的执行协议一致。
     */
    private Compiled plan(boolean verifyUnit) {
        return new Compiled("SELECT value FROM energy", List.of("zone-a"), List.of(new Column("value", "读数")),
                List.of(), "kWh", "", verifyUnit, null, null);
    }
}
