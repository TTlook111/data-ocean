package com.dataocean.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;

import java.time.LocalDateTime;

import org.apache.ibatis.binding.MapperMethod;
import org.junit.jupiter.api.Test;

/**
 * 校验 PO 时间列的读取映射和生成 SQL 的写入边界。
 */
class PersistenceTimestampTest {

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

        // 实际业务变更仍可通过 Wrapper 将数据库 UTC 时间与业务字段一并写入。
        var wrapper = Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source.getId())
                .set(DatasourcePO::getName, source.getName())
                .setSql("updated_at=UTC_TIMESTAMP(3)");
        var wrapperParameters = new MapperMethod.ParamMap<Object>();
        wrapperParameters.put("et", null);
        wrapperParameters.put("ew", wrapper);
        String explicitUpdate = configuration
                .getMappedStatement(statementPrefix + "update")
                .getBoundSql(wrapperParameters)
                .getSql();
        assertThat(explicitUpdate).contains("updated_at=UTC_TIMESTAMP(3)").doesNotContain("created_at");
    }
}
