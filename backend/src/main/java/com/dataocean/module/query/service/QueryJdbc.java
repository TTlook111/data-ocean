package com.dataocean.module.query.service;

import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.source.SourceConnection;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.governance.service.MetricRules;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QuerySql.Compiled;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 复用来源连接和只读账号保护，负责绑定参数、限制资源和实际执行。
 */
@Service
@RequiredArgsConstructor
class QueryJdbc {

    /** 已核对的真实连接及其查询限制。 */
    private final DatasourceMapper sources;
    /** 采集共用的安全 JDBC 通道。 */
    private final SourceJdbc jdbc;
    /** 凭据只在打开真实连接时解密。 */
    private final SourceSecretCodec cipher;
    /**
     * 按真实源执行编号保留本机 JDBC 句柄，供核实及取消。
     */
    private final ConcurrentHashMap<String, PreparedStatement> inFlight = new ConcurrentHashMap<>();

    /** 从已授权目录和固定运算编译读语句。 */
    Compiled compile(QueryCorpus.Basis basis, Plan plan) {
        var source = basis.source();
        var config = sources.revision(source.id(), source.configurationId()).configuration();
        return new QuerySql().compile(basis, plan, "MYSQL".equals(config.getDatabaseType()), config.getDatabaseName());
    }

    /**
     * 根据真实源列比较输入参数，使用同一只读连接和单次超时。
     */
    boolean sameTextValue(QueryCorpus.Basis basis, Field field, String first, String second, int remaining) {
        var source = basis.source();
        var revision = sources.revision(source.id(), source.configurationId());
        var target = connectionTarget(revision, remaining);
        var object = basis.tables()
                .get(field.table())
                .entry()
                .observation()
                .structure()
                .object();
        try (var connection = jdbc.open(target, password(source), "do-query-compare")) {
            if (!jdbc.readOnlySafety(connection, target)) {
                // 目录和参数比较同样必须确认源端账号可只读。
                throw QueryFailure.denied();
            }
            return jdbc.compareText(
                    connection, target.type(), object, field.column(), first, second, target.queryTimeoutSeconds());
        } catch (SQLException unavailable) {
            // 无法校验当前源的实际规则时，不能武断排除或采用候选。
            throw QueryFailure.technical();
        }
    }

    /**
     * 执行已登记的原 SQL，资源关闭前捕获完整结果。
     */
    void execute(Source source, String execution, Compiled compiled, Runnable permit, Consumer<QueryDataVO> capture)
            throws SQLException {
        var revision = sources.revision(source.id(), source.configurationId());
        int seconds = Math.min(30, revision.limits().getQueryTimeoutSeconds());
        var target = connectionTarget(revision, seconds);
        try (var connection = jdbc.open(target, password(source), "do-query-" + execution);
                var statement = connection.prepareStatement(compiled.sql())) {
            if (!jdbc.readOnlySafety(connection, target)) {
                // 动态账号安全检查失败时不派发模型生成的当前业务请求。
                throw QueryFailure.denied();
            }
            statement.setQueryTimeout(seconds);
            statement.setMaxRows(2001);
            for (int i = 0; i < compiled.parameters().size(); i++) {
                statement.setObject(i + 1, compiled.parameters().get(i));
            }
            inFlight.put(execution, statement);
            try {
                // 只有登记到固定执行代次后才发送这条确切语句。
                permit.run();
                try (var result = statement.executeQuery()) {
                    // 捕获失败时不发送第二条 SQL，原执行留待核实。
                    capture.accept(read(result, compiled));
                }
            } finally {
                // 原句柄已退出，后续只能回读持久化结果。
                inFlight.remove(execution, statement);
            }
        }
    }

    /**
     * 读取已派发语句的限量结果和实际单位，供原执行捕获。
     */
    private QueryDataVO read(ResultSet result, Compiled compiled) throws SQLException {
        var rows = new ArrayList<List<String>>();
        String displayUnit = compiled.unit();
        String measuredUnit = null;
        while (result.next()) {
            if (rows.size() == 2000) {
                return new QueryDataVO(
                        compiled.columns(), rows, true, displayUnit, compiled.scope(), null, compiled.series());
            }
            var values = new ArrayList<String>();
            for (int col = 1; col <= compiled.columns().size(); col++) {
                var value = result.getString(col);
                if (value != null && value.length() > 200) {
                    // 不存储超出此工作台支持范围的单元大对象。
                    throw QueryFailure.resource();
                }
                values.add(value);
            }
            rows.add(values);
            if (compiled.verifiesUnit() && values.get(values.size() - 1) != null) {
                var current = unit(result, compiled.columns().size());
                if (current != null) {
                    // 空计数行可以保留零，同一序列的实际参与行须使用同一个单位。
                    if (measuredUnit != null && !measuredUnit.equals(current)) {
                        throw QueryFailure.knowledge();
                    }
                    measuredUnit = current;
                    displayUnit = current;
                }
            }
        }
        return new QueryDataVO(compiled.columns(), rows, false, displayUnit, compiled.scope(), null, compiled.series());
    }

    /**
     * 核对原执行是否仍持有本机 JDBC 句柄。
     */
    boolean isInFlight(String execution) {
        return inFlight.containsKey(execution);
    }

    /**
     * 源连接由固定修订和单次操作时限决定。
     */
    private SourceConnection connectionTarget(DatasourceMapper.RevisionRow revision, int budget) {
        var config = revision.configuration();
        return new SourceConnection(
                SourceConnection.Type.valueOf(config.getDatabaseType()),
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getSchemas(),
                config.getAccount(),
                Math.min(budget, revision.limits().getQueryTimeoutSeconds()));
    }

    /**
     * 只在当前已授权只读连接建立时读取冻结的凭据修订。
     */
    private String password(Source source) {
        var credential = sources.credential(source.id(), source.configurationId());
        return cipher.decrypt(credential.ciphertext(), "datasource:credential:" + source.id(), credential.keyVersion());
    }

    /**
     * 核验参与行的单位完整性和一致性。没有参与行时保留无单位的计数结果。
     */
    private String unit(ResultSet rows, int resultColumns) throws SQLException {
        if (!rows.getBoolean(resultColumns + 3)) {
            // 有参与行缺少单位，即使其余行单位一致也不能交付。
            throw QueryFailure.knowledge();
        }
        var value = rows.getString(resultColumns + 1);
        if (value != null && (!rows.getBoolean(resultColumns + 2) || !MetricRules.validUnit(value))) {
            // 数据库按精确值检测混用，交付格式与治理固定单位统一。
            throw QueryFailure.knowledge();
        }
        return value;
    }

    /**
     * 发出对原 JDBC 语句的取消，最终状态仍需核实。
     */
    void cancel(String execution) {
        var statement = inFlight.get(execution);
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException unavailable) {
                // 原执行读取或超时负责核实最终状态，不记伪停止。
            }
        }
    }
}
