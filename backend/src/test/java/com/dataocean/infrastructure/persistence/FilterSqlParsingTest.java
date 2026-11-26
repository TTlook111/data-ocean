package com.dataocean.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.module.audit.entity.query.AuditQuery;
import com.dataocean.module.audit.mapper.AuditMapper;
import com.dataocean.module.iam.entity.query.PersonQuery;
import com.dataocean.module.iam.mapper.UserMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

import net.sf.jsqlparser.JSQLParserException;

/**
 * 使用生产 Mapper 生成动态 SQL，再按实际分页解析路径验证筛选条件没有被空行截断。
 */
class FilterSqlParsingTest {

    /**
     * 操作者与日期取交集时，日期条件及全部绑定位置必须保留。
     */
    @Test
    void auditActorAndDatesKeepAllConditions() throws JSQLParserException {
        // 操作者和日期之间的可选条件保持空值，以复现连续空行。
        AuditQuery query = new AuditQuery();
        query.setActor("核对人员");

        MapperMethod.ParamMap<Object> parameters = new MapperMethod.ParamMap<>();
        parameters.put("page", new Page<>(1, 10));
        parameters.put("q", query);
        parameters.put("from", LocalDateTime.of(2026, 11, 16, 16, 0));
        parameters.put("until", LocalDateTime.of(2026, 11, 17, 16, 0));

        BoundSql bound = generatedSql(AuditMapper.class, parameters);
        String parsed = JsqlParserGlobal.parse(bound.getSql()).toString();

        assertTrue(parsed.contains("a.occurred_at >= ?"));
        assertTrue(parsed.contains("a.occurred_at < ?"));
        assertEquals(bound.getParameterMappings().size(), placeholders(parsed));
    }

    /**
     * 人员名称与账号状态同时查询时，缺省的中间条件不能导致状态筛选消失。
     */
    @Test
    void userNameAndStatusKeepAllConditions() throws JSQLParserException {
        PersonQuery query = new PersonQuery();
        query.setDisplayName("核对人员");
        query.setDisplayStatus("DISABLED");

        MapperMethod.ParamMap<Object> parameters = new MapperMethod.ParamMap<>();
        parameters.put("page", new Page<>(1, 10));
        parameters.put("q", query);
        parameters.put("departments", null);
        parameters.put("roleId", null);

        BoundSql bound = generatedSql(UserMapper.class, parameters);
        String parsed = JsqlParserGlobal.parse(bound.getSql()).toString();

        assertTrue(parsed.contains("u.must_change_password"));
        assertEquals(bound.getParameterMappings().size(), placeholders(parsed));
    }

    /**
     * 显式空的部门范围生成可分页计数的恒假条件。
     */
    @Test
    void emptyDepartmentScopeDoesNotMatchPeople() throws JSQLParserException {
        var parameters = new MapperMethod.ParamMap<Object>();
        parameters.put("page", new Page<>(1, 10));
        parameters.put("q", new PersonQuery());
        parameters.put("departments", List.of());
        parameters.put("roleId", null);

        BoundSql bound = generatedSql(UserMapper.class, parameters);
        String parsed = JsqlParserGlobal.parse(bound.getSql()).toString();
        assertTrue(parsed.contains("WHERE 1 = 0"));
        assertTrue(bound.getParameterMappings().isEmpty());
    }

    /**
     * 直接读取生产注解，不在测试中复制另一份 SQL。
     */
    private BoundSql generatedSql(Class<?> mapper, Map<String, Object> parameters) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(mapper);
        return configuration.getMappedStatement(mapper.getName() + ".page").getBoundSql(parameters);
    }

    /**
     * 本用例的 SQL 字面值不含问号，计数用于核对实际保留的参数占位。
     */
    private long placeholders(String sql) {
        return sql.chars().filter(value -> value == '?').count();
    }
}
