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
import com.dataocean.module.datasource.mapper.CatalogMapper.Observation;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CredentialRow;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.mapper.GovernanceCatalogMapper.Column;
import com.dataocean.module.datasource.service.GovernanceCatalog.Entry;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.query.entity.vo.QueryColumnVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryCorpus.Table;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QuerySql.Compiled;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        when(sources.credential(71, 5)).thenReturn(new CredentialRow("fixture", new byte[] {1}));
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
        assertThat(data.getRows()).hasSize(2000);
        assertThat(data.isTruncated()).isTrue();
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
                .isInstanceOf(QueryFailure.class)
                .hasMessage("PERMISSION_DENIED");
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
                .isInstanceOf(QueryFailure.class)
                .hasMessage("RESOURCE_EXCEEDED");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("4.0");
        when(rows.getString(2)).thenReturn("kWh", "MWh");
        when(rows.getBoolean(3)).thenReturn(true);
        when(rows.getBoolean(4)).thenReturn(true);
        assertThatThrownBy(() -> query.execute(source, "x", plan(true), () -> 2, permit))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 按查询核验列区分有效单位、混用、空白、空计数和缺失单位。
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "110|kg_CO2e|true|true|true",
                "110|' '|true|true|false",
                "110|mWh|false|true|false",
                "0||false|true|true",
                "110||false|false|false"
            })
    void deliversOnlyVerifiedUnitsOrAnEmptyCount(
            String value, String unit, boolean uniform, boolean complete, boolean delivers) throws SQLException {
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(value);
        when(rows.getString(2)).thenReturn(unit);
        when(rows.getBoolean(3)).thenReturn(uniform);
        when(rows.getBoolean(4)).thenReturn(complete);

        if (delivers) {
            // 零值保留为空范围的真实计数，非空范围采用确认通过的实际单位。
            var data = query.execute(source, "x", plan(true), () -> 2, permit);
            assertThat(data.getRows()).containsExactly(List.of(value));
            assertThat(data.getUnit()).isEqualTo(unit == null ? "" : unit);
        } else {
            // 混用、空白及参与行缺少单位都不能交付金额。
            assertThatThrownBy(() -> query.execute(source, "x", plan(true), () -> 2, permit))
                    .isInstanceOf(QueryFailure.class)
                    .hasMessage("KNOWLEDGE_INSUFFICIENT");
        }
    }

    /**
     * 比较固定和用户筛选时使用实际源列与只读身份，故障不会降级为猜测等值。
     */
    @Test
    void usesTheFrozenPhysicalColumnAndReadOnlySourceForTextComparison() throws SQLException {
        var object = new ObjectRef("energy", "energy", "meter", "TABLE", null, null, null);
        var structure = new Structure(object, List.of(), List.of(), List.of(), List.of());
        var observation = new Observation(1, 1, 1L, 1L, null, null, null, structure, null);
        var column = new Column(2, 3, "site", "varchar", false, "", 1);
        var table = new Table(new Entry(1, 1, false, observation, List.of(column)), Map.of("site", "站点"));
        var site = new Field("meter", "site");
        var basis = new Basis(source, null, 0, "", Map.of("meter", table), Map.of(), Map.of(), Map.of(site, "站点"));
        var plan = new Plan("READY", null, "6", "VALUES", null, null, List.of(site), List.of());
        assertThat(query.compile(basis, plan).sql()).contains("FROM `energy`.`meter`");

        when(sourceJdbc.open(any(), any(), eq("do-query-compare"))).thenReturn(connection);
        when(sourceJdbc.compareText(connection, Type.MYSQL, object, "site", "华东", "華東", 3))
                .thenReturn(true)
                .thenThrow(new SQLException("no confirmed collation"));
        assertThat(query.sameTextValue(basis, site, "华东", "華東", 3)).isTrue();
        assertThatThrownBy(() -> query.sameTextValue(basis, site, "华东", "華東", 3))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("TECHNICAL_FAILURE");

        when(sourceJdbc.readOnlySafety(eq(connection), any())).thenReturn(false);
        assertThatThrownBy(() -> query.sameTextValue(basis, site, "华东", "華東", 3))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("PERMISSION_DENIED");
        verify(statement, never()).executeQuery();
    }

    /**
     * 构造有绑定参数的限量输出，与正式编译的执行协议一致。
     */
    private Compiled plan(boolean verifyUnit) {
        return new Compiled(
                "SELECT value FROM energy",
                List.of("zone-a"),
                List.of(new QueryColumnVO("value", "读数")),
                List.of(),
                verifyUnit ? "" : "kWh",
                "",
                verifyUnit,
                null,
                null,
                Set.of());
    }
}
