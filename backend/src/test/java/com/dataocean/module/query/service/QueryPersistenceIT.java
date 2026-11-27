package com.dataocean.module.query.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.dataocean.module.feedback.mapper.FeedbackQueryIssueMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Input;
import com.dataocean.module.query.mapper.QueryRequestMapper.Permit;

import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 用实际 MySQL 验证问数状态转换，所有记录留在测试事务内。
 */
@EnabledIfEnvironmentVariable(named = "DO_QUERY_TEST_MYSQL_URL", matches = ".+")
class QueryPersistenceIT {

    /** 有效捕获可以交付，已清理或过期的结果不能变成已交付。 */
    @Test
    void deliversOnlyCurrentCapturedResult() {
        withSession(session -> {
            var requests = session.getMapper(QueryRequestMapper.class);
            var missing = seed(session, "FINALIZING", "SUCCEEDED", null, -5, 4, 60, null);
            var expired = seed(session, "FINALIZING", "SUCCEEDED", "{}", -90000, 4, 60, null);
            var current = seed(session, "FINALIZING", "SUCCEEDED", "{}", -5, 4, 60, null);

            assertThat(requests.deliver(missing, 4)).isZero();
            assertThat(requests.deliver(expired, 4)).isZero();
            assertThat(requests.deliver(current, 4)).isOne();
            assertThat(requests.get(current).state()).isEqualTo("RESULT_DELIVERED");
        });
    }

    /** 崩溃接管后，旧代次的心跳、执行许可和结果均不能落盘。 */
    @Test
    void rejectsWritesFromPreviousLease() {
        withSession(session -> {
            var requests = session.getMapper(QueryRequestMapper.class);
            var id = seed(session, "CHECKING", "NOT_SUBMITTED", null, 0, 1, -1, null);
            var execution = UUID.randomUUID().toString();
            var fingerprint = new byte[32];

            assertThat(requests.claim(id, 1)).isOne();
            assertThat(requests.renew(id, 1)).isZero();
            assertThat(requests.permit(new Permit(id, 1, execution, 1, "rules", "{}", fingerprint, null)))
                    .isZero();
            assertThat(requests.permit(new Permit(id, 2, execution, 1, "rules", "{}", fingerprint, null)))
                    .isOne();
            assertThat(requests.capture(id, 1, execution, "{}")).isZero();
            assertThat(requests.capture(id, 2, execution, "{}")).isOne();
            assertThat(requests.get(id).executionId()).isEqualTo(execution);
        });
    }

    /** 未知执行按当前用户、来源和指纹保留，不能借不同问题原文绕过核对。 */
    @Test
    void findsEquivalentUnresolvedExecutionOnlyInSameScope() {
        withSession(session -> {
            var requests = session.getMapper(QueryRequestMapper.class);
            var fingerprint = new byte[32];
            fingerprint[0] = 9;
            var known = seed(session, "TECHNICAL_FAILURE", "UNKNOWN", null, -5, 1, -1, fingerprint);
            var other = UUID.randomUUID().toString();

            assertThat(requests.hasUnresolvedExecution(-77301, 1, fingerprint, other))
                    .isTrue();
            assertThat(requests.hasUnresolvedExecution(-77301, 1, fingerprint, known))
                    .isFalse();
            assertThat(requests.hasUnresolvedExecution(-77301, 2, fingerprint, other))
                    .isFalse();
            assertThat(requests.hasUnresolvedExecution(-77302, 1, fingerprint, other))
                    .isFalse();
            assertThat(requests.hasUnresolvedExecution(-77301, 1, new byte[32], other))
                    .isFalse();
        });
    }

    /**
     * 实际平台库对不同原问复用开放事项，并按核实的缺口或独立原问保留证据。
     */
    @Test
    void reusesConfirmedOpenGapAcrossQuestions() {
        withSession(session -> {
            // 新建事务内的合成来源，真实反馈外键也参与验证。
            long source = seedSource(session);
            String sameGap = "1".repeat(64);
            long first = intakeGap(session, source, "查订单金额", sameGap);
            long repeated = intakeGap(session, source, "订单总额是多少", sameGap);
            long other = intakeGap(session, source, "查退货数量", "2".repeat(64));
            long unknown = intakeGap(session, source, "查无法确认的口径", null);
            long anotherUnknown = intakeGap(session, source, "查另一个无法确认的口径", null);

            assertThat(repeated).isEqualTo(first);
            assertThat(List.of(first, other, unknown, anotherUnknown)).doesNotHaveDuplicates();
            assertThat(session.getMapper(FeedbackQueryIssueMapper.class).incidents(first))
                    .hasSize(2);
        });
    }

    /**
     * 通过实际结算和入队 SQL 读回指纹、事项编号和原问关联。
     */
    private long intakeGap(SqlSession session, long source, String question, String gap) {
        var requests = session.getMapper(QueryRequestMapper.class);
        var issues = session.getMapper(FeedbackQueryIssueMapper.class);
        var id = UUID.randomUUID().toString();
        requests.insert(new Input(id, -77301, 1, source, id, null, question, id, false, null, null, null));
        assertThat(requests.claim(id, 0)).isOne();
        assertThat(requests.stop(
                        id, 1, "KNOWLEDGE_INSUFFICIENT", "NOT_SUBMITTED", "INSUFFICIENT_KNOWLEDGE", "rules", gap))
                .isOne();

        var settled = requests.get(id);
        assertThat(settled.failureGapKey()).isEqualTo(gap);
        String origin = "QUERY_KNOWLEDGE_GAP";
        issues.intake(source, origin, id, "KNOWLEDGE", settled.failureGapKey());
        long issue = gap == null ? issues.atRequest(origin, id) : issues.openGap(source, gap);
        issues.link(id, issue);
        assertThat(issues.linkedIssue(id)).isEqualTo(issue);
        return issue;
    }

    /**
     * 创建仅在本测试事务中可见的来源，不依赖已有验收数据。
     */
    private long seedSource(SqlSession session) {
        var sql =
                """
                INSERT INTO ds_source(name,name_key,lifecycle,created_by,created_at,updated_at)
                VALUES(?,?,'DRAFT',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """;
        try (var insert = session.getConnection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // 使用独立名称避免和真实或并行验收记录重复。
            var name = "feedback-gap-" + UUID.randomUUID();
            insert.setString(1, name);
            insert.setString(2, name);
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                // 证据和事项都使用实际写入的来源编号。
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        } catch (SQLException failure) {
            // 保留底层诊断交给集成测试报告。
            throw new IllegalStateException("无法建立隔离来源", failure);
        }
    }

    /** 明确指定库存验收平台库，禁止在通用开发或生产数据库写测试数据。 */
    private void withSession(Consumer<SqlSession> test) {
        var url = System.getenv("DO_QUERY_TEST_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/dataocean_inventory_e2e_[0-9]+(?:\\?.*)?");
        var source = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                url,
                System.getenv("DO_QUERY_TEST_MYSQL_USER"),
                System.getenv("DO_QUERY_TEST_MYSQL_PASSWORD"));
        var config = new MybatisConfiguration(new Environment("query-review", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.setArgNameBasedConstructorAutoMapping(true);
        config.addMapper(QueryRequestMapper.class);
        config.addMapper(FeedbackQueryIssueMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(false)) {
            // 真实表只接受未提交的测试记录，其他工作进程不可见。
            test.accept(session);
            session.rollback(true);
        }
    }

    /** 精确布置目标状态与数据库时钟下的结果年龄及租约。 */
    private String seed(
            SqlSession session,
            String state,
            String execution,
            String result,
            int ageSeconds,
            int run,
            int leaseSeconds,
            byte[] fingerprint) {
        var id = UUID.randomUUID().toString();
        var sql =
                """
                INSERT INTO qry_request(id,user_id,authentication_version,source_id,command_id,question,state,
                    execution_state,result,processing_started_at,executed_at,conversation_id,run_version,lease_until,
                    execution_fingerprint)
                VALUES(?,-77301,1,1,?,'数据库边界测试',?,?,?,UTC_TIMESTAMP(3),
                    TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),?,?,
                    TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(3)),?)
                """;
        try (var insert = session.getConnection().prepareStatement(sql)) {
            insert.setString(1, id);
            insert.setString(2, id);
            insert.setString(3, state);
            insert.setString(4, execution);
            insert.setString(5, result);
            insert.setInt(6, ageSeconds);
            insert.setString(7, id);
            insert.setInt(8, run);
            insert.setInt(9, leaseSeconds);
            if (fingerprint == null) {
                insert.setNull(10, Types.BINARY);
            } else {
                insert.setBytes(10, fingerprint);
            }
            insert.executeUpdate();
            return id;
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("无法建立隔离测试记录", failure);
        }
    }
}
