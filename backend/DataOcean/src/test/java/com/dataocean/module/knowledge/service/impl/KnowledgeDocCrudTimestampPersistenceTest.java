package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.dataocean.common.config.MyBatisPlusConfig;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.po.KnowledgeDocVersion;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real mapper/fill/optimistic-lock transactions, without booting or contacting application services. */
class KnowledgeDocCrudTimestampPersistenceTest {
    private static final LocalDateTime OLD_TIME = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

    @BeforeEach
    void authenticateTestEditor() {
        var user = new LoginUser(7L, "test-manager", "unused", "测试维护者", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void clearAuthentication() {
        UserContext.clear();
    }

    @Test
    void contentEditRefreshesThePersistedAndListedTimeAlongsideItsNewVersion() {
        try (var fixture = new Fixture()) {
            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            fixture.service.updateDoc(6L, "更新标题", "新正文", 0, "正常人工保存");

            var saved = fixture.docs.selectById(6L);
            var version = fixture.versions.selectOne(new LambdaQueryWrapper<KnowledgeDocVersion>()
                    .eq(KnowledgeDocVersion::getDocId, 6L).eq(KnowledgeDocVersion::getVersionNo, 2));
            assertThat(saved.getUpdatedAt()).isAfter(OLD_TIME).isAfterOrEqualTo(before);
            assertThat(saved.getCreatedAt()).isEqualTo(OLD_TIME);
            assertThat(saved.getCurrentVersion()).isEqualTo(2);
            assertThat(saved.getVersion()).isEqualTo(1);
            assertThat(saved.getStatus()).isEqualTo("DRAFT");
            assertThat(version.getCreatedAt()).isAfterOrEqualTo(saved.getUpdatedAt());
            assertThat(version.getContent()).isEqualTo("新正文");
            var listed = fixture.service.listDocsInDatasources(List.of(5L), 5L, null, 1, 10);
            assertThat(listed.getRecords()).singleElement()
                    .satisfies(doc -> assertThat(doc.getUpdatedAt()).isEqualTo(saved.getUpdatedAt()));
        }
    }

    @Test
    void titleOnlySaveRefreshesTimeWithoutCreatingAContentVersion() {
        try (var fixture = new Fixture()) {
            fixture.service.updateDoc(6L, "只改标题", "原正文", 0, "仅标题变化");

            var saved = fixture.docs.selectById(6L);
            assertThat(saved.getUpdatedAt()).isAfter(OLD_TIME);
            assertThat(saved.getCreatedAt()).isEqualTo(OLD_TIME);
            assertThat(saved.getCurrentVersion()).isEqualTo(1);
            assertThat(fixture.versions.selectCount(null)).isEqualTo(1);
        }
    }

    @Test
    void aStaleSaveDoesNotAdvanceTimeOrCreateAnotherVersion() {
        try (var fixture = new Fixture()) {
            assertThatThrownBy(() -> fixture.service.updateDoc(6L, "冲突标题", "冲突正文", 99, "不得落库"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode()).isEqualTo(409));

            var saved = fixture.docs.selectById(6L);
            assertThat(saved.getUpdatedAt()).isEqualTo(OLD_TIME);
            assertThat(saved.getCreatedAt()).isEqualTo(OLD_TIME);
            assertThat(saved.getCurrentVersion()).isEqualTo(1);
            assertThat(saved.getContent()).isEqualTo("原正文");
            assertThat(fixture.versions.selectCount(null)).isEqualTo(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcTemplate jdbc;
        final KnowledgeDocMapper docs;
        final KnowledgeDocVersionMapper versions;
        final KnowledgeDocCrudService service;

        Fixture() {
            var source = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:knowledge_time_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE knowledge_doc (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, datasource_id BIGINT, title VARCHAR(200), content CLOB, current_version INT, status VARCHAR(30), review_status VARCHAR(30), updated_by BIGINT, table_names CLOB, version INT, created_at TIMESTAMP, updated_at TIMESTAMP, deleted INT DEFAULT 0)");
            jdbc.execute("CREATE TABLE knowledge_doc_version (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, doc_id BIGINT, datasource_id BIGINT, metadata_snapshot_id BIGINT, dependency_snapshot CLOB, version_no INT, content CLOB, generation_source VARCHAR(30), review_status VARCHAR(30), reviewer_id BIGINT, change_summary VARCHAR(500), created_by BIGINT, created_at TIMESTAMP, UNIQUE(doc_id,version_no))");
            jdbc.update("INSERT INTO knowledge_doc (id,datasource_id,title,content,current_version,status,version,created_at,updated_at,deleted) VALUES (6,5,?,?,1,'DRAFT',0,?,?,0)",
                    "原标题", "原正文", OLD_TIME, OLD_TIME);
            jdbc.update("INSERT INTO knowledge_doc_version (doc_id,datasource_id,metadata_snapshot_id,dependency_snapshot,version_no,content,generation_source,created_by,created_at) VALUES (6,5,77,'{}',1,?,'MANUAL',7,?)",
                    "原正文", OLD_TIME);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setEnvironment(new Environment("isolated-knowledge-time", new SpringManagedTransactionFactory(), source));
            var applicationConfig = new MyBatisPlusConfig();
            GlobalConfigUtils.getGlobalConfig(configuration).setMetaObjectHandler(applicationConfig.metaObjectHandler());
            configuration.addInterceptor(applicationConfig.mybatisPlusInterceptor());
            configuration.addMapper(KnowledgeDocMapper.class);
            configuration.addMapper(KnowledgeDocVersionMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
            docs = session.getMapper(KnowledgeDocMapper.class);
            versions = session.getMapper(KnowledgeDocVersionMapper.class);
            var dependencies = mock(KnowledgeDependencySnapshotBuilder.class);
            when(dependencies.build(5L, 77L, "MANUAL")).thenReturn("{}");
            var helper = new KnowledgeDocHelper(docs, versions, mock(KnowledgeChunkMapper.class));
            var proxy = new ProxyFactory(new KnowledgeDocCrudService(docs, versions, dependencies, helper));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                    new AnnotationTransactionAttributeSource()));
            service = (KnowledgeDocCrudService) proxy.getProxy();
        }

        @Override
        public void close() {
            jdbc.execute("SHUTDOWN");
        }
    }
}
