package com.dataocean.module.permission.s1.mapper;

import com.dataocean.module.permission.s1.entity.IamS1UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 H2 事务和 Mapper SQL 验证最后管理员锁，而不是只 mock 行锁查询。 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(statements = {
        "DROP TABLE IF EXISTS iam_s1_user_role",
        "DROP TABLE IF EXISTS iam_s1_role",
        "CREATE TABLE iam_s1_role (id BIGINT PRIMARY KEY, role_code VARCHAR(100), role_name VARCHAR(100), description VARCHAR(500), status INT, protected_role INT, built_in INT, created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)",
        "CREATE TABLE iam_s1_user_role (id BIGINT PRIMARY KEY, user_id BIGINT, role_id BIGINT, status INT, revision_no BIGINT, created_by BIGINT, updated_by BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP)",
        "INSERT INTO iam_s1_role (id, role_code, role_name, status, protected_role, built_in) VALUES (1, 'IAM_S1_SYSTEM_ADMIN', '系统管理员', 1, 1, 1)",
        "INSERT INTO iam_s1_user_role (id, user_id, role_id, status, revision_no) VALUES (1, 10, 1, 1, 1)",
        "INSERT INTO iam_s1_user_role (id, user_id, role_id, status, revision_no) VALUES (2, 11, 1, 1, 1)"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(statements = {
        "DROP TABLE IF EXISTS iam_s1_user_role",
        "DROP TABLE IF EXISTS iam_s1_role"
}, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class IamS1ProtectedAdminConcurrencyIntegrationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private IamS1RoleMapper roleMapper;
    @Autowired
    private IamS1UserRoleMapper userRoleMapper;

    @Test
    void protectedRoleLockMakesSecondTransactionSeeOneRemainingBinding() throws Exception {
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                roleMapper.selectActiveProtectedRoleForUpdate();
                firstLocked.countDown();
                await(releaseFirst);
                userRoleMapper.deleteById(1L);
            }));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Integer> second = executor.submit(() -> transactionTemplate.execute(status -> {
                roleMapper.selectActiveProtectedRoleForUpdate();
                List<IamS1UserRole> bindings = userRoleMapper.selectActiveProtectedBindingsForUpdate();
                return bindings.size();
            }));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发测试等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", exception);
        }
    }
}
