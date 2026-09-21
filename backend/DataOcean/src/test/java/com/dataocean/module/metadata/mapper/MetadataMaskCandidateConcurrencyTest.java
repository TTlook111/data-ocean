package com.dataocean.module.metadata.mapper;

import com.dataocean.module.metadata.entity.DbColumnMeta;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 H2 事务验证掩码确认依赖的行锁语义。
 *
 * <p>`iam_s1_field_protection` 没有唯一约束（V55），所以“先查再插”的模式只有在
 * 同一把行锁上串行化时才并发安全。本测试直接验证 `DbColumnMetaMapper.selectForUpdate`
 * 会让第二个事务阻塞到第一个提交之后——`MetadataMaskCandidateServiceImpl.confirm`
 * 正是先拿这把锁、再做当前读判断候选是否已被处理。</p>
 *
 * <p>这里验证的是锁机制本身；service 是否按“先锁后读”的顺序调用由
 * `MetadataMaskCandidateServiceImplTest.confirmSerialisesOnTheColumnRowBeforeReadingTheCandidate` 钉住。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(statements = {
        "DROP TABLE IF EXISTS db_column_meta",
        "CREATE TABLE db_column_meta (id BIGINT PRIMARY KEY, snapshot_id BIGINT, table_name VARCHAR(200), column_name VARCHAR(200))",
        "INSERT INTO db_column_meta (id, snapshot_id, table_name, column_name) VALUES (77, 8, 'orders', 'phone')"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {"DROP TABLE IF EXISTS db_column_meta"}, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class MetadataMaskCandidateConcurrencyTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private DbColumnMetaMapper columnMetaMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void secondConfirmWaitsForTheFirstToCommit() throws Exception {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 事务一：取行锁后停住，模拟“正在确认同一字段”。
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                columnMetaMapper.selectForUpdate(8L, "orders", "phone");
                firstLocked.countDown();
                await(releaseFirst);
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            // 事务二：同一行的锁定请求必须等事务一释放，而不是立刻返回。
            Future<Long> second = executor.submit(() -> transactionTemplate.execute(status -> {
                long startedAt = System.nanoTime();
                columnMetaMapper.selectForUpdate(8L, "orders", "phone");
                return System.nanoTime() - startedAt;
            }));

            // 事务一还持锁时，事务二不应完成。
            Thread.sleep(200);
            assertThat(second.isDone()).as("持锁期间第二个事务不应完成").isFalse();

            releaseFirst.countDown();
            assertThat(second.get(5, TimeUnit.SECONDS)).isGreaterThan(0L);
            first.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lockedRowReadsTheCommittedValueNotAnOldSnapshot() throws Exception {
        // 锁后的当前读必须看到等待期间其他事务的提交：
        // 若用普通 SELECT，REPEATABLE READ 会返回事务开始时的旧快照，
        // 第二个事务会误判“候选仍在”而重复写入保护。
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                columnMetaMapper.selectForUpdate(8L, "orders", "phone");
                firstLocked.countDown();
                await(releaseFirst);
                jdbcTemplate.update("UPDATE db_column_meta SET column_name = 'phone_masked' WHERE id = 77");
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> second = executor.submit(() -> transactionTemplate.execute(status -> {
                DbColumnMeta column = columnMetaMapper.selectForUpdate(8L, "orders", "phone");
                return column == null ? null : column.getColumnName();
            }));

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);

            // 事务一改名后再释放锁，事务二用旧条件已查不到，说明它读到的是最新已提交状态。
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
