package com.dataocean.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dataocean.module.datasource.entity.po.CollectionSnapshotPO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.CollectionSnapshotMapper;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.mapper.RoleMapper;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;

import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 校验 PO 时间列的读取映射和生成 SQL 的写入边界。
 */
class PersistenceTimestampTest {

    /**
     * 创建 SQL 使用单条语句写入两个数据库时间，并将 JDBC 返回的编号写回当前 PO。
     */
    @Test
    void datasourceInsertUsesDatabaseTimeAndReturnsGeneratedId() throws SQLException {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(DatasourceRecordMapper.class);
        var insert = configuration.getMappedStatement(DatasourceRecordMapper.class.getName() + ".create");

        // 使用 Mapper 实际接收的命名参数构造插入语句。
        var source = new DatasourcePO();
        source.setName("示例数据源");
        source.setLifecycle("DRAFT");
        var parameters = new MapperMethod.ParamMap<Object>();
        parameters.put("row", source);
        parameters.put("nameKey", new byte[] {1});
        parameters.put("actor", 1L);
        assertThat(insert.getBoundSql(parameters).getSql())
                .contains("created_at,updated_at", "VALUES (?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");

        backfillId(insert, parameters);
        assertThat(source.getId()).isEqualTo(21L);
    }

    /**
     * 新建角色保留默认状态和版本，由当前插入返回编号。
     */
    @Test
    void roleInsertBindsBuiltinFlagAndReturnsGeneratedId() throws SQLException {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(RoleMapper.class);
        var insert = configuration.getMappedStatement(RoleMapper.class.getName() + ".insertCreated");
        var role = new RolePO();
        role.setBuiltin(true);

        var bound = insert.getBoundSql(role);
        assertThat(bound.getSql())
                .contains("is_builtin", "created_at,updated_at")
                .doesNotContain("lifecycle", "version");
        assertThat(bound.getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .contains("builtin");
        assertThat(configuration.newMetaObject(role).getValue("builtin")).isEqualTo(true);
        backfillId(insert, role);
        assertThat(role.getId()).isEqualTo(21L);
    }

    /**
     * BaseMapper 创建候选快照时仍写入只在插入中使用的范围和比较协议。
     */
    @Test
    void snapshotInsertBindsRequiredFieldsWithoutAStoredId() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(CollectionSnapshotMapper.class);
        var insert = configuration.getMappedStatement(CollectionSnapshotMapper.class.getName() + ".insert");
        var row = new CollectionSnapshotPO();
        row.setSourceId(12L);
        row.setConfigRevisionId(3L);
        row.setTaskId("candidate");
        row.setScopeKey(new byte[] {1});
        row.setComparisonContractKey(new byte[] {2});
        row.setAdapterVersion("1");
        row.setComparisonVersion("1");
        row.setStatus("CANDIDATE");

        assertThat(insert.getBoundSql(row).getParameterMappings())
                .extracting(ParameterMapping::getProperty)
                .containsExactlyInAnyOrder(
                        "sourceId",
                        "configRevisionId",
                        "taskId",
                        "scopeKey",
                        "comparisonContractKey",
                        "adapterVersion",
                        "comparisonVersion",
                        "status");
    }

    /**
     * 模拟单条插入的 JDBC 生成键，由实际 Mapper 配置写回原参数。
     */
    private void backfillId(MappedStatement insert, Object parameters) throws SQLException {
        // 模拟 JDBC 返回一条自增主键，交给 MyBatis 配置的回填逻辑处理。
        var statement = mock(Statement.class);
        var keys = mock(ResultSet.class);
        var metadata = mock(ResultSetMetaData.class);
        when(statement.getGeneratedKeys()).thenReturn(keys);
        when(keys.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnType(1)).thenReturn(Types.BIGINT);
        when(keys.next()).thenReturn(true, false);
        when(keys.getLong(1)).thenReturn(21L);

        insert.getKeyGenerator().processAfter(null, insert, statement, parameters);
    }

    /**
     * 读出的时间字段可用于展示，实体更新时由业务写入语句显式维护。
     */
    @Test
    void entityUpdateLeavesPersistedTimestampsToExplicitSql() {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DatasourceRecordMapper.class);
        String statementPrefix = DatasourceRecordMapper.class.getName() + ".";

        // 模拟从数据库读取并修改的数据源 PO，时间字段已带有旧值。
        var source = new DatasourcePO();
        source.setId(21L);
        source.setName("修改后的名称");
        source.setCreatedAt(LocalDateTime.of(2026, 11, 17, 5, 0));
        source.setUpdatedAt(LocalDateTime.of(2026, 11, 17, 6, 0));

        String select = configuration
                .getMappedStatement(statementPrefix + "selectById")
                .getBoundSql(source.getId())
                .getSql();
        assertThat(select).contains("created_at", "updated_at");

        var entityParameters = new MapperMethod.ParamMap<Object>();
        entityParameters.put("et", source);
        String entityUpdate = configuration
                .getMappedStatement(statementPrefix + "updateById")
                .getBoundSql(entityParameters)
                .getSql();
        assertThat(entityUpdate).contains("name").doesNotContain("created_at", "updated_at");

        // 使用实际发布方法生成 Wrapper，检查写入快照指针时是否同步刷新更新时间。
        var mapper = mock(DatasourceRecordMapper.class, CALLS_REAL_METHODS);
        var wrapper = ArgumentCaptor.<Wrapper<DatasourcePO>>captor();
        mapper.publishSnapshot(source.getId(), 51L);
        verify(mapper).update(isNull(), wrapper.capture());
        var wrapperParameters = new MapperMethod.ParamMap<Object>();
        wrapperParameters.put("et", null);
        wrapperParameters.put("ew", wrapper.getValue());
        String explicitUpdate = configuration
                .getMappedStatement(statementPrefix + "update")
                .getBoundSql(wrapperParameters)
                .getSql();
        assertThat(explicitUpdate)
                .contains("current_snapshot_id=", "updated_at=UTC_TIMESTAMP(3)")
                .doesNotContain("created_at");
    }
}
