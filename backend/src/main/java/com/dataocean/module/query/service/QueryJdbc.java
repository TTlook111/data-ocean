package com.dataocean.module.query.service;

import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.datasource.source.SourceConnection;
import com.dataocean.module.datasource.source.SourceJdbc;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.query.service.QueryProtocol.Data;
import com.dataocean.module.query.service.QueryProtocol.Plan;
import com.dataocean.module.query.service.QuerySql.Compiled;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

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
    /** 每次派发只在本进程保留 JDBC 句柄，供取消操作使用。 */
    private final ConcurrentHashMap<String, PreparedStatement> inFlight = new ConcurrentHashMap<>();

    /** 从已授权目录和固定运算编译读语句。 */
    Compiled compile(QueryCorpus.Basis basis, Plan plan) {
        var source = basis.source();
        var config = sources.revision(source.id(), source.configurationId()).configuration();
        return new QuerySql().compile(basis, plan, "MYSQL".equals(config.getDatabaseType()), config.getDatabaseName());
    }

    /** 执行一次真实源端读，SQL 派发前由调用方登记许可。 */
    Data execute(Source source, String request, Compiled compiled, IntSupplier remaining, Runnable permit)
            throws SQLException {
        var revision = sources.revision(source.id(), source.configurationId());
        var config = revision.configuration();
        int seconds = Math.min(30, revision.limits().getQueryTimeoutSeconds());
        var target = new SourceConnection(
                SourceConnection.Type.valueOf(config.getDatabaseType()),
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getSchemas(),
                config.getAccount(),
                seconds);
        var credential = sources.credential(source.id(), source.configurationId());
        String password = cipher.decrypt(
                credential.ciphertext(), "datasource:credential:" + source.id(), credential.keyVersion());
        try (var connection = jdbc.open(target, password, "do-query-" + request);
                var statement = connection.prepareStatement(compiled.sql())) {
            if (!jdbc.readOnlySafety(connection, target)) {
                // 动态账号安全检查失败时不派发模型生成的当前业务请求。
                throw QueryFailure.denied();
            }
            statement.setQueryTimeout(Math.min(seconds, remaining.getAsInt()));
            statement.setMaxRows(2001);
            for (int i = 0; i < compiled.parameters().size(); i++) {
                statement.setObject(i + 1, compiled.parameters().get(i));
            }
            inFlight.put(request, statement);
            try {
                // 只有登记到固定执行代次后才发送这条确切语句。
                permit.run();
                try (var result = statement.executeQuery()) {
                    var rows = new ArrayList<List<String>>();
                    String displayUnit = compiled.unit();
                    String measuredUnit = null;
                    while (result.next()) {
                        if (rows.size() == 2000) {
                            return new Data(
                                    compiled.columns(),
                                    rows,
                                    true,
                                    displayUnit,
                                    compiled.scope(),
                                    null,
                                    compiled.series());
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
                            if (measuredUnit != null && !measuredUnit.equals(current)) {
                                // 日序列整条曲线只能按同一个真实单位解释。
                                throw QueryFailure.knowledge();
                            }
                            measuredUnit = current;
                            displayUnit = current;
                        }
                    }
                    return new Data(
                            compiled.columns(), rows, false, displayUnit, compiled.scope(), null, compiled.series());
                }
            } finally {
                inFlight.remove(request, statement);
            }
        }
    }

    /** 拒绝混合或缺失计量单位，只展示数据库同组实际单位。 */
    private String unit(ResultSet rows, int resultColumns) throws SQLException {
        var first = rows.getString(resultColumns + 1);
        var last = rows.getString(resultColumns + 2);
        if (first == null
                || !first.equals(last)
                || !rows.getBoolean(resultColumns + 3)
                || !first.matches("[\\p{L}\\p{N} ./%-]{1,12}")) {
            throw QueryFailure.knowledge();
        }
        return first;
    }

    /** 发出对该轮真实 JDBC 语句的取消，调用成功不等同于源端最终状态。 */
    void cancel(String request) {
        var statement = inFlight.get(request);
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException unavailable) {
                // 原执行读取或超时负责核实最终状态，不记伪停止。
            }
        }
    }
}
