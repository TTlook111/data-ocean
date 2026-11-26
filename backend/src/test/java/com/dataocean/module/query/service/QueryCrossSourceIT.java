package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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
import com.dataocean.module.datasource.source.SourceConnection;
import com.dataocean.module.datasource.source.SourceConnectionProperties;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceMetadata.Key;
import com.dataocean.module.datasource.source.SourceMetadata.ObjectRef;
import com.dataocean.module.datasource.source.SourceMetadata.Structure;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.entity.vo.GovernanceTargetVO;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryCorpus.Table;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QueryProtocol.TimeSelection;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.json.JsonMapper;

/**
 * 两个真实 MySQL 合成业务库采用相同表名。目录输入固定，JDBC 和业务源执行使用正式实现。
 */
@EnabledIfEnvironmentVariable(named = "DO_QUERY_BUSINESS_FIXTURE", matches = ".+")
class QueryCrossSourceIT {

    /** 使用服务端相同的 JSON 类型读取外部只读验收配置。 */
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * 能耗使用维表关联、带小数的净读数、空日和真实计量单位。
     */
    @Test
    void energyUsesGovernedJoinsAndBusinessDays() throws Exception {
        var runtime = runtime("energy", 71L);
        Field amount = new Field("daily_facts", "amount");
        Field day = new Field("daily_facts", "business_day");
        Field unit = new Field("daily_facts", "uom");
        Field fk = new Field("daily_facts", "zone_id");
        Field key = new Field("zone", "id");
        Field zone = new Field("zone", "name");
        var tables = Map.of(
                "daily_facts",
                        table(
                                runtime,
                                "daily_facts",
                                List.of(),
                                col(1, "zone_id", "int"),
                                col(2, "business_day", "date"),
                                col(3, "amount", "decimal(12,3)"),
                                col(4, "uom", "varchar(12)")),
                "zone",
                        table(
                                runtime,
                                "zone",
                                List.of(new Key("PRIMARY", "id", 1, null, null, true)),
                                col(5, "id", "int"),
                                col(6, "name", "varchar(60)")));
        var metric = fact(
                "9",
                "METRIC",
                "净用电量",
                """
                {"rule":"SUM(daily_facts.amount)","meaning":"采集读数净额",
                 "usage":"按业务日期计算，同组计量单位必须一致。",
                 "metricRules":{"dateMode":"BUSINESS_DAY","dateFieldId":"2","fixedFilters":[],"unitMode":"COLUMN","unitFieldId":"4"}}
                """,
                "1",
                "2",
                "3",
                "4");
        var relation = fact(
                "10", "RELATION", "分区读数", """
                {"pairs":[{"left":"1","right":"5"}]}
                """);
        var basis = new Basis(
                runtime.source(),
                null,
                0,
                "同一业务日可出现多条读数。",
                tables,
                Map.of("1", fk, "2", day, "3", amount, "4", unit, "5", key, "6", zone),
                Map.of("9", metric, "10", relation),
                Map.of(amount, "净用电量", day, "读数日", unit, "计量单位", fk, "分区主键", key, "主键", zone, "车间"));
        var plan = new Plan(
                "READY",
                null,
                "88",
                "SUM",
                "净用电量",
                null,
                List.of(amount),
                List.of(new Condition(zone, "装配区")),
                new TimeSelection(day, "TREND", null, null, null));
        var sql = new QuerySql();
        var scoped = sql.resolveTime(basis, plan, LocalDate.of(2026, 11, 22), "装配区净用电量走势", false);
        var compiled = runtime.jdbc().compile(basis, scoped);
        var permits = new AtomicInteger();
        var data =
                runtime.jdbc().execute(runtime.source(), "energy-trend", compiled, () -> 5, permits::incrementAndGet);
        assertThat(permits).hasValue(1);
        assertThat(data.getSeries().getStart()).isEqualTo("2026-11-16");
        assertThat(data.getUnit()).isEqualTo("kWh");
        assertThat(data.getRows())
                .containsExactly(
                        List.of("2026-11-17", "82.125"),
                        List.of("2026-11-18", "0.000"),
                        List.of("2026-11-20", "9.250"));
        assertThat(data.getRows().get(2).get(1))
                .isEqualTo(
                        oracle(
                                runtime,
                                """
                SELECT SUM(f.amount) FROM daily_facts f JOIN zone z ON f.zone_id=z.id
                WHERE z.name='装配区' AND f.business_day='2026-11-20'
                """));

        // 全区直接相加会把 kWh 与 MWh 混在一起，源端真实单位检查必须拒绝。
        var mixed = new Plan(
                "READY",
                null,
                "88",
                "SUM",
                "净用电量",
                null,
                List.of(amount),
                List.of(),
                new TimeSelection(day, "DAY", "2026-11-20", "2026-11-20", false));
        assertThatThrownBy(() -> runtime.jdbc()
                        .execute(runtime.source(), "mixed", runtime.jdbc().compile(basis, mixed), () -> 5, () -> {}))
                .isInstanceOf(QueryFailure.class)
                .hasMessage("KNOWLEDGE_INSUFFICIENT");
    }

    /**
     * 物流按状态、路线和业务日去重，同名的其他行业表不能被读取。
     */
    @Test
    void deliveryDoesNotReuseEnergySchemaOrFilters() throws Exception {
        var runtime = runtime("delivery", 72L);
        Field id = new Field("daily_facts", "amount");
        Field day = new Field("daily_facts", "business_day");
        Field route = new Field("daily_facts", "route_code");
        Field state = new Field("daily_facts", "signed_state");
        var table = table(
                runtime,
                "daily_facts",
                List.of(),
                col(1, "amount", "int"),
                col(2, "business_day", "date"),
                col(3, "route_code", "varchar(30)"),
                col(4, "signed_state", "varchar(12)"));
        var metric = fact(
                "9",
                "METRIC",
                "签收运单量",
                """
                {"rule":"COUNT(DISTINCT daily_facts.amount)","meaning":"运单号去重","usage":"单位为件",
                 "metricRules":{"dateMode":"NONE","fixedFilters":[],"unitMode":"FIXED","unit":"件"}}
                """);
        var basis = new Basis(
                runtime.source(),
                null,
                0,
                "重复事件按运单去重。",
                Map.of("daily_facts", table),
                Map.of(),
                Map.of("9", metric),
                Map.of(id, "运单号", day, "业务日", route, "线路", state, "妥投状态"));
        var plan = new Plan(
                "READY",
                null,
                "89",
                "COUNT_DISTINCT",
                "签收运单量",
                null,
                List.of(id),
                List.of(new Condition(route, "T-17"), new Condition(state, "签收"), new Condition(day, "2026-11-20")));
        var compiled = runtime.jdbc().compile(basis, plan);
        var actual = runtime.jdbc().execute(runtime.source(), "delivery-count", compiled, () -> 5, () -> {});
        assertThat(new BigDecimal(actual.getRows().get(0).get(0))).isEqualByComparingTo("1");
        assertThat(actual.getUnit()).isEqualTo("件");
        assertThat(actual.getRows().get(0).get(0))
                .isEqualTo(
                        oracle(
                                runtime,
                                """
                SELECT COUNT(DISTINCT amount) FROM daily_facts
                WHERE route_code='T-17' AND signed_state='签收' AND business_day='2026-11-20'
                """));
        assertThat(compiled.sql()).contains("do_query_e2e_delivery_20261122").doesNotContain("do_query_e2e_energy");
        assertThatThrownBy(() -> oracle(runtime, "SELECT amount FROM do_query_e2e_energy_20261122.daily_facts"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("denied");
    }

    /**
     * 仅在明确指定的合成配置下使用 TLS 和各自的真实只读账号。
     */
    private Runtime runtime(String type, long id) throws Exception {
        var config = json.readTree(Files.readString(Path.of(System.getenv("DO_QUERY_BUSINESS_FIXTURE"))));
        var selected = config.path(type);
        String database = selected.path("database").asText();
        assertThat(database).matches("do_query_e2e_(energy|delivery)_[0-9]+");
        String user = selected.path("username").asText();
        String password = selected.path("password").asText();
        var destination =
                new SourceConnection(SourceConnection.Type.MYSQL, "127.0.0.1", 3306, database, List.of(), user, 10);
        var policy = new SourceConnectionProperties();
        policy.setAllowedEndpoints(Set.of("127.0.0.1:3306"));
        policy.setMysqlTrustStore(config.path("trustStore").asText());
        policy.setMysqlTrustStorePassword(config.path("trustStorePassword").asText());
        policy.setMysqlVerifyCaOnly(true);
        var sourceJdbc = new SourceJdbc(policy);
        var configuration = new SourceConfigurationDTO();
        configuration.setDatabaseType("MYSQL");
        configuration.setHost("127.0.0.1");
        configuration.setPort(3306);
        configuration.setDatabaseName(database);
        configuration.setAccount(user);
        configuration.setSchemas(List.of());
        var limits = new RuntimePolicyDTO();
        limits.setQueryTimeoutSeconds(10);
        var sources = mock(DatasourceMapper.class);
        var cipher = mock(SourceSecretCodec.class);
        var token = new byte[] {1};
        when(sources.revision(id, 4)).thenReturn(new RevisionRow(4, 1, 5L, configuration, limits, null));
        when(sources.credential(id, 4)).thenReturn(new CredentialRow("test", token));
        when(cipher.decrypt(token, "datasource:credential:" + id, "test")).thenReturn(password);
        var source = new Source(id, type, "ENABLED", 4L, 3L, 1, false, null, null, null, null);
        return new Runtime(source, destination, sourceJdbc, new QueryJdbc(sources, sourceJdbc, cipher), password);
    }

    /**
     * 独立只读原始 SQL 是具体行业预期值的来源。
     */
    private String oracle(Runtime runtime, String sql) throws SQLException {
        try (var connection = runtime.sourceJdbc().open(runtime.destination(), runtime.password(), "do-query-oracle");
                var statement = connection.prepareStatement(sql)) {
            assertThat(runtime.sourceJdbc().readOnlySafety(connection, runtime.destination()))
                    .isTrue();
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    /**
     * 测试目录保留合成库声明的物理主键、表名和字段类型。
     */
    private Table table(Runtime runtime, String name, List<Key> primary, Column... columns) {
        var ref = new ObjectRef(runtime.destination().database(), "", name, "TABLE", null, null, null);
        var structure = new Structure(ref, List.of(), primary, List.of(), List.of());
        var observation = new Observation(1, 1, 4L, 4L, null, null, null, structure, null);
        return new Table(new Entry(1, 1, false, observation, List.of(columns)), Map.of());
    }

    /**
     * 为安全编译提供合成目录字段编号。
     */
    private Column col(long id, String name, String type) {
        return new Column(id, id, name, type, false, "", (int) id);
    }

    /**
     * 构造和正式发布对象同形的固定指标与关联。
     */
    private GovernanceFactVO fact(String id, String kind, String label, String body, String... deps) {
        var fields = List.of(deps).stream()
                .map(n -> new GovernanceTargetVO("1", "COLUMN", n))
                .toList();
        return new GovernanceFactVO(
                new GovernanceTargetVO("1", kind, id), "1", 1, id, label, json.readTree(body), fields);
    }

    /** 实际驱动及其对应的合成源配置。 */
    private record Runtime(
            Source source, SourceConnection destination, SourceJdbc sourceJdbc, QueryJdbc jdbc, String password) {}
}
