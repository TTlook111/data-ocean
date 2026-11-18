package com.dataocean.module.datasource.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.dataocean.common.PageQuery;
import com.dataocean.infrastructure.persistence.MybatisConfig;
import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.mapper.CapabilityCheckRecordMapper;
import com.dataocean.module.datasource.mapper.CollectionExecutionMapper;
import com.dataocean.module.datasource.mapper.CollectionMapper;
import com.dataocean.module.datasource.mapper.CollectionSnapshotMapper;
import com.dataocean.module.datasource.mapper.CollectionTaskMapper;
import com.dataocean.module.datasource.mapper.DatasourceConfigurationMapper;
import com.dataocean.module.datasource.mapper.DatasourceCredentialMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.iam.entity.dto.NewUserProfileDTO;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.po.RolePO;
import com.dataocean.module.iam.entity.query.PersonQuery;
import com.dataocean.module.iam.entity.query.RoleQuery;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.mapper.PermissionMapper;
import com.dataocean.module.iam.mapper.RoleAssignmentMapper;
import com.dataocean.module.iam.mapper.RoleMapper;
import com.dataocean.module.iam.mapper.UserMapper;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 在单独创建的平台验收库验证 Flyway 基线和数据源模块约束。
 */
@EnabledIfEnvironmentVariable(named = "DO_IT_SOURCE_ENABLED", matches = "true")
class DatasourceMigrationIT {

    /**
     * 使用仅拥有目标测试库权限的账号验证实际表与 Flyway 版本。
     */
    @Test
    void appliesToIsolatedPlatformSchema() throws Exception {
        String db = "do_platform_acceptance_20261118";
        String url = "jdbc:mysql://127.0.0.1:3307/" + db + "?sslMode=REQUIRED";
        String account = "do_platform_it_20261118";
        String password = System.getenv("DO_IT_PLATFORM_PASSWORD");
        try (var connection = DriverManager.getConnection(url, account, password);
                var statement = connection.createStatement();
                var database = statement.executeQuery("SELECT DATABASE()")) {
            // 重核当前库身份，只允许这个合成平台测试库接受迁移。
            assertThat(database.next()).isTrue();
            assertThat(database.getString(1)).isEqualTo(db);
        }

        Flyway flyway = Flyway.configure().dataSource(url, account, password).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");

        String sql =
                """
                SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN
                    ('ds_source','ds_connection_revision','ds_collection_task','ds_collection_execution','ds_snapshot',
                     'ds_object_revision','ds_change','ds_connection_change')
                """;
        try (var connection = DriverManager.getConnection(url, account, password);
                var statement = connection.createStatement();
                var tables = statement.executeQuery(sql)) {
            // 最终状态必须存在于真实 MySQL 数据字典。
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).isEqualTo(8);
        }
        verifiesMapperRoundTrip(url, account, password);
    }

    /**
     * 在已核对的隔离库验证 MyBatis 生成键、配置映射、实际分页及采集写入，关闭时回滚样例数据。
     */
    private void verifiesMapperRoundTrip(String url, String account, String password) {
        var database = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, account, password);
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("datasource-it", new JdbcTransactionFactory(), database));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.addInterceptor(new MybatisConfig().mybatisPlusInterceptor());
        for (var mapper : List.of(
                UserMapper.class,
                RoleMapper.class,
                RoleAssignmentMapper.class,
                PermissionMapper.class,
                OutboxMapper.class,
                DatasourceRecordMapper.class,
                DatasourceConfigurationMapper.class,
                DatasourceCredentialMapper.class,
                CapabilityCheckRecordMapper.class,
                DatasourcePageMapper.class,
                CollectionTaskMapper.class,
                CollectionExecutionMapper.class,
                CollectionSnapshotMapper.class)) {
            // 注册正式 Mapper，校验与应用一致的 SQL 和类型映射。
            configuration.addMapper(mapper);
        }

        try (var session =
                new MybatisSqlSessionFactoryBuilder().build(configuration).openSession()) {
            // 合成记录共享前缀，整个链路使用同一个可回滚事务。
            String sample = "mpit_" + UUID.randomUUID().toString().substring(0, 8);
            var profile = new NewUserProfileDTO();
            profile.setUsername(sample);
            profile.setDisplayName(sample);
            var users = session.getMapper(UserMapper.class);
            long actor = users.create(profile, null);
            assertThat(users.selectById(actor).getUsername()).isEqualTo(sample);
            var roles = session.getMapper(RoleMapper.class);
            long role = roles.create(new RoleProfileDTO(sample, null, List.of()), sample, sample, false, actor);
            assertThat(roles.selectById(role).getName()).isEqualTo(sample);

            // IAM 列表通过白名单分页入口，极大页码由插件按实际结果校正。
            var roleQuery = new RoleQuery();
            roleQuery.setName(sample);
            roleQuery.setPageNum(Long.MAX_VALUE).setPageSize(2L);
            var rolePage = roles.page(roleQuery.<RolePO>toMpPage("updated_at", false), roleQuery);
            assertThat(rolePage.getCurrent()).isEqualTo(1);
            assertThat(rolePage.getRecords()).extracting(RolePO::getId).containsExactly(role);

            // 使用真实 MySQL 检查空范围与角色批量统计，不把限定条件丢给分页插件。
            var emptyPeople = users.page(new PageQuery().toFixedOrderMpPage(), new PersonQuery(), List.of(), null);
            assertThat(emptyPeople.getTotal()).isZero();
            assertThat(emptyPeople.getRecords()).isEmpty();
            assertThat(roles.appendSnapshot(role, "[]", "[]", actor)).isEqualTo(1);
            var assignments = session.getMapper(RoleAssignmentMapper.class);
            assertThat(assignments.assign(actor, role, 1, actor)).isEqualTo(1);
            assertThat(assignments.assignmentsForUsers(List.of(actor))).hasSize(1);
            assertThat(assignments.memberCounts(List.of(role)))
                    .containsExactly(new RoleAssignmentMapper.MemberCount(role, 1));
            assertThat(session.getMapper(PermissionMapper.class).protectedRoles(List.of(role)))
                    .isEmpty();
            verifiesOutboxLeases(session.getMapper(OutboxMapper.class), actor);

            var records = session.getMapper(DatasourceRecordMapper.class);
            var sources = new DatasourceMapper(
                    session.getMapper(DatasourceConfigurationMapper.class),
                    records,
                    session.getMapper(DatasourceCredentialMapper.class),
                    session.getMapper(CapabilityCheckRecordMapper.class));
            byte[] scope = new byte[32];
            long source = sources.insertSource(sample, sample.getBytes(StandardCharsets.UTF_8), "ENABLED", actor);
            long credential =
                    sources.insertCredential(source, actor, new DatasourceMapper.CredentialRow("1", new byte[] {1}));
            var sourceConfig = new SourceConfigurationDTO();
            sourceConfig.setDatabaseType("POSTGRESQL");
            sourceConfig.setHost("localhost");
            sourceConfig.setPort(5432);
            sourceConfig.setDatabaseName("integration");
            sourceConfig.setAccount("read_only");
            sourceConfig.setTechnicalContactId(Long.toString(actor));
            sourceConfig.setSchemas(List.of("it_first", "it_second"));
            long revision = sources.insertConfiguration(
                    source, actor, 1, credential, sourceConfig, new RuntimePolicyDTO(), scope);
            sources.initialConfiguration(source, revision);
            var stored = sources.revision(source, revision);
            assertThat(stored.configuration().getSchemas()).containsExactly("it_first", "it_second");
            assertThat(sources.revisions(source, List.of(revision))
                            .get(revision)
                            .configuration()
                            .getSchemas())
                    .containsExactly("it_first", "it_second");
            assertThat(stored.configuration().getTechnicalContactId()).isEqualTo(Long.toString(actor));
            assertThat(sources.credential(source, revision).ciphertext()).containsExactly((byte) 1);
            long check = sources.insertCheck(
                    source, actor, 1, scope, scope, "1", "1", "15", "[]", "{}", "{}", "PASS", "PASS");
            var evidence = sources.check(check);
            assertThat(evidence.sourceId()).isEqualTo(source);
            assertThat(evidence.valid()).isTrue();
            assertThat(evidence.created()).endsWith("Z");
            var initial = sources.get(source);
            assertThat(initial.getUpdatedAt()).isEqualTo(initial.getCreatedAt());

            // 极大页码由插件使用指定计数 SQL 校正，不提前计算会溢出的偏移。
            var pages = session.getMapper(DatasourcePageMapper.class);
            var page =
                    new PageQuery().setPageNum(Long.MAX_VALUE).<DatasourcePageMapper.SourceSummary>toFixedOrderMpPage();
            page.setCountId("sourceCount");
            var filtered = pages.sourcePage(page, sample, "", "", "");
            assertThat(filtered.getCurrent()).isEqualTo(1);
            assertThat(filtered.getTotal()).isEqualTo(1);
            assertThat(filtered.getRecords())
                    .extracting(DatasourcePageMapper.SourceSummary::id)
                    .containsExactly(source);

            var snapshots = session.getMapper(CollectionSnapshotMapper.class);
            var tasks = new CollectionMapper(
                    session.getMapper(CollectionTaskMapper.class),
                    session.getMapper(CollectionExecutionMapper.class),
                    records,
                    snapshots);
            String taskId = UUID.randomUUID().toString();
            tasks.accept(
                    taskId,
                    UUID.randomUUID().toString(),
                    source,
                    stored,
                    actor,
                    "{}",
                    null,
                    null,
                    null,
                    null,
                    null,
                    scope);
            var task = tasks.task(taskId, false);
            var snapshot = tasks.snapshot(taskId);
            assertThat(task.getCreatedAt()).isNotNull();
            assertThat(snapshot.getStatus()).isEqualTo("CANDIDATE");
            assertThat(tasks.claim(task, sample, 60)).isTrue();
            assertThat(tasks.claim(task, sample, 60)).isFalse();
            assertThat(tasks.enumerated(tasks.task(taskId, false), 0)).isTrue();
            assertThat(snapshots.publish(source, snapshot.getId(), null, null, "INITIAL_BASELINE", 0))
                    .isEqualTo(1);
            records.publishSnapshot(source, snapshot.getId());
            assertThat(sources.get(source).getCurrentSnapshotId()).isEqualTo(snapshot.getId());
            assertThat(sources.get(source).getCreatedAt()).isEqualTo(initial.getCreatedAt());
            assertThat(tasks.finishSuccess(tasks.task(taskId, false))).isTrue();
            assertThat(tasks.task(taskId, false).getState()).isEqualTo("SUCCEEDED");
            assertThat(tasks.snapshotsForTasks(List.of(taskId))).hasSize(1);
            session.rollback();
        }
    }

    /**
     * 验证租约更新拒绝旧令牌，当前租约可以重试或确认送达。
     */
    private void verifiesOutboxLeases(OutboxMapper outbox, long actor) {
        // 只在当前事务中创建合成事件，回滚时和其他验收样例一起撤销。
        var event = new OutboxPO();
        String eventId = UUID.randomUUID().toString();
        event.setEventId(eventId);
        event.setEventType("iam.integration");
        event.setAggregateType("user");
        event.setAggregateId(Long.toString(actor));
        event.setAggregateVersion(1);
        event.setPayload("{}");
        assertThat(outbox.append(event)).isEqualTo(1);

        String firstLease = UUID.randomUUID().toString();
        assertThat(outbox.claim(eventId, firstLease)).isEqualTo(1);
        assertThat(outbox.retry(eventId, firstLease)).isEqualTo(1);
        var pending = outbox.selectById(eventId);
        assertThat(pending.getDeliveryState()).isEqualTo("PENDING");
        assertThat(pending.getLeaseToken()).isNull();

        String secondLease = UUID.randomUUID().toString();
        assertThat(outbox.claim(eventId, secondLease)).isEqualTo(1);
        assertThat(outbox.delivered(eventId, firstLease)).isZero();
        assertThat(outbox.retry(eventId, firstLease)).isZero();
        assertThat(outbox.delivered(eventId, secondLease)).isEqualTo(1);
        var delivered = outbox.selectById(eventId);
        assertThat(delivered.getAttempts()).isEqualTo(2);
        assertThat(delivered.getDeliveredAt()).isNotNull();
        assertThat(delivered.getLeasedUntil()).isNull();

        assertThat(outbox.hasReceipt(eventId, "integration")).isFalse();
        assertThat(outbox.receipt(eventId, "integration")).isEqualTo(1);
        assertThat(outbox.hasReceipt(eventId, "integration")).isTrue();
    }
}
