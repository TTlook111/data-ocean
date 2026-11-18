package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.entity.po.CapabilityCheckPO;
import com.dataocean.module.datasource.entity.po.DatasourceCredentialPO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 保存数据源配置、秘密引用与分项测试证据。写事务由上层业务协调。
 */
@Repository
@RequiredArgsConstructor
public class DatasourceMapper {

    /**
     * DataOcean 平台库的 JDBC 接口，业务源连接独立建立。
     */
    private final JdbcTemplate sql;

    /**
     * 数据源身份与配置指针的单表访问。
     */
    private final DatasourceRecordMapper records;

    /**
     * 保存和按归属读取独立的加密凭据。
     */
    private final DatasourceCredentialMapper credentials;

    /**
     * 按主键和归属登记已验证的检查报告。
     */
    private final CapabilityCheckRecordMapper checks;

    /**
     * 已保存的分项测试证据，内部摘要仅用于核对关联。
     *
     * @param id 测试编号
     * @param actorId 测试人编号
     * @param sourceId 已绑定源编号，未保存源时为空
     * @param baseVersion 测试时的配置版本
     * @param configurationFingerprint 非秘密连接要素摘要
     * @param credentialFingerprint 凭据摘要
     * @param adapterVersion 测试适配器版本
     * @param policyVersion 连接安全策略版本
     * @param created 测试时间文本，使用 UTC
     * @param expires 测试到期时间文本，使用 UTC
     * @param valid 测试是否正在有效期内
     * @param report 实际测试结果的类型化 JSON
     */
    public record CheckRow(
            long id,
            long actorId,
            Long sourceId,
            Integer baseVersion,
            byte[] configurationFingerprint,
            byte[] credentialFingerprint,
            String adapterVersion,
            String policyVersion,
            String created,
            String expires,
            boolean valid,
            String report) {}

    /**
     * 平台保存的密文及其解密版本，不能作为接口返回。
     *
     * @param keyVersion 部署密钥版本
     * @param ciphertext 经认证的密文封装
     */
    public record CredentialRow(String keyVersion, byte[] ciphertext) {}

    /**
     * 一次采集固定使用的已保存配置。
     *
     * @param id 配置修订编号
     * @param revision 源内修订序号
     * @param credentialId 已固定的运行凭据编号
     * @param configuration 该修订的非秘密配置
     * @param limits 该修订的资源与调度参数
     * @param scopeKey 固定配置范围摘要
     */
    public record RevisionRow(
            long id,
            int revision,
            Long credentialId,
            SourceConfigurationDTO configuration,
            RuntimePolicyDTO limits,
            byte[] scopeKey) {}

    /**
     * 按主键读取当前配置和占位，不接触秘密正文。
     */
    public DatasourcePO get(long id) {
        return records.selectById(id);
    }

    /**
     * 锁定正在执行变更的源，与任务接收及目录切换共用占位。
     */
    public DatasourcePO lock(long id) {
        return records.lock(id);
    }

    /**
     * 读取固定配置的明确字段，当前账户秘密由专用凭据查询取得。
     */
    public RevisionRow revision(long source, long revision) {
        List<String> schemas = sql.queryForList(
                """
                SELECT x.schema_name FROM ds_connection_schema x JOIN ds_connection_revision r ON r.id=x.connection_revision_id
                WHERE r.source_id=? AND r.id=? ORDER BY x.ordinal
                """,
                String.class,
                source,
                revision);
        return sql
                .query(
                        """
                SELECT r.*,s.name AS source_label FROM ds_connection_revision r JOIN ds_source s ON s.id=r.source_id
                WHERE r.source_id=? AND r.id=?
                """,
                        (row, index) -> {
                            // 连接、Schema 与运行参数均来自同一修订。
                            var configuration = new SourceConfigurationDTO();
                            configuration.setName(row.getString("source_label"));
                            configuration.setDatabaseType(row.getString("database_type"));
                            configuration.setHost(row.getString("host"));
                            configuration.setPort(row.getObject("port", Integer.class));
                            configuration.setDatabaseName(row.getString("database_name"));
                            configuration.setAccount(row.getString("account"));
                            configuration.setPurpose(row.getString("purpose"));
                            configuration.setTechnicalContactId(row.getString("technical_contact_id"));
                            configuration.setBusinessContactId(row.getString("business_contact_id"));
                            configuration.setSchemas(schemas);
                            var runtime = new RuntimePolicyDTO();
                            runtime.setMaxConnections(row.getInt("max_connections"));
                            runtime.setCollectionConcurrency(row.getInt("collection_concurrency"));
                            runtime.setRetryLimit(row.getInt("retry_limit"));
                            runtime.setTaskTimeoutSeconds(row.getInt("task_timeout_seconds"));
                            runtime.setQueryTimeoutSeconds(row.getInt("query_timeout_seconds"));
                            runtime.setRowLimit(row.getInt("row_limit"));
                            runtime.setSyncHours(row.getInt("sync_hours"));
                            runtime.setWindowStart(LocalTime.ofSecondOfDay(row.getInt("window_start_minute") * 60L)
                                    .toString());
                            runtime.setWindowEnd(LocalTime.ofSecondOfDay(row.getInt("window_end_minute") * 60L)
                                    .toString());
                            return new RevisionRow(
                                    row.getLong("id"),
                                    row.getInt("revision"),
                                    row.getObject("credential_revision_id", Long.class),
                                    configuration,
                                    runtime,
                                    row.getBytes("scope_key"));
                        },
                        source,
                        revision)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 取得指定源、指定配置的已加密运行凭据。
     */
    public CredentialRow credential(long id, long revision) {
        return sql
                .query(
                        """
                        SELECT c.key_version,c.ciphertext FROM ds_connection_revision r
                        JOIN ds_credential_revision c ON c.id=r.credential_revision_id AND c.source_id=r.source_id
                        WHERE r.source_id=? AND r.id=?
                        """,
                        (row, index) -> new CredentialRow(row.getString(1), row.getBytes(2)),
                        id,
                        revision)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 写入新数据源身份，配置指针由同一事务在创建修订后补齐。
     */
    public long insertSource(String name, byte[] nameKey, String lifecycle, long actor) {
        return insert(
                """
                INSERT INTO ds_source (name,name_key,lifecycle,created_by,created_at,updated_at)
                VALUES (?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
                """,
                name,
                nameKey,
                lifecycle,
                actor);
    }

    /**
     * 保存不可变密文并返回数据库生成的修订编号。
     */
    public long insertCredential(long source, long actor, CredentialRow credential) {
        var row = new DatasourceCredentialPO();
        row.setSourceId(source);
        row.setKeyVersion(credential.keyVersion());
        row.setCiphertext(credential.ciphertext());
        row.setCreatedBy(actor);
        credentials.create(row);
        return row.getId();
    }

    /**
     * 保存不可变的配置修订及其 Schema 成员。
     */
    public long insertConfiguration(
            long id,
            long actor,
            int revision,
            Long credential,
            SourceConfigurationDTO config,
            RuntimePolicyDTO limits,
            byte[] scopeKey) {
        long configurationId = insert(
                """
                INSERT INTO ds_connection_revision (source_id,revision,database_type,host,port,database_name,account,
                    credential_revision_id,purpose,technical_contact_id,business_contact_id,max_connections,collection_concurrency,
                    retry_limit,task_timeout_seconds,query_timeout_seconds,row_limit,sync_hours,window_start_minute,
                    window_end_minute,scope_key,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))
                """,
                id,
                revision,
                config.getDatabaseType(),
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getAccount(),
                credential,
                config.getPurpose(),
                config.getTechnicalContactId(),
                config.getBusinessContactId(),
                limits.getMaxConnections(),
                limits.getCollectionConcurrency(),
                limits.getRetryLimit(),
                limits.getTaskTimeoutSeconds(),
                limits.getQueryTimeoutSeconds(),
                limits.getRowLimit(),
                limits.getSyncHours(),
                LocalTime.parse(limits.getWindowStart()).toSecondOfDay() / 60,
                LocalTime.parse(limits.getWindowEnd()).toSecondOfDay() / 60,
                scopeKey,
                actor);

        List<String> schemas = config.getSchemas();
        for (int index = 0; index < schemas.size(); index++) {
            // Schema 的原始名称作为来源身份，显示顺序不进入采集范围摘要。
            String schema = schemas.get(index);
            sql.update(
                    "INSERT INTO ds_connection_schema VALUES (?,?,?,?)",
                    configurationId,
                    schema,
                    schema.getBytes(StandardCharsets.UTF_8),
                    index + 1);
        }
        return configurationId;
    }

    /**
     * 将新建源指向同事务写入的配置。
     */
    public void initialConfiguration(long source, long configuration) {
        records.update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .set(DatasourcePO::getCurrentConnectionRevisionId, configuration));
    }

    /**
     * 保存服务端报告及连接绑定，秘密摘要不进入公开 JSON。
     */
    public long insertCheck(
            Long source,
            long actor,
            Integer version,
            byte[] connection,
            byte[] credential,
            String adapterVersion,
            String policyVersion,
            String detectedVersion,
            String verified,
            String coverage,
            String results,
            String safety,
            String business) {
        return insert(
                """
                INSERT INTO ds_capability_check (source_id,actor_id,base_version,connection_fingerprint,credential_fingerprint,
                    fingerprint_key_version,adapter_version,policy_version,detected_version,verified_scope,coverage,results,
                    read_only_safety,business_read_status,checked_at,expires_at)
                VALUES (?,?,?,?,?,'1',?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)
                """,
                source,
                actor,
                version,
                connection,
                credential,
                adapterVersion,
                policyVersion,
                detectedVersion,
                verified,
                coverage,
                results,
                safety,
                business);
    }

    /**
     * 在平台数据库时间内判断测试是否过期。
     */
    public CheckRow check(long id) {
        return sql
                .query(
                        """
                        SELECT *, expires_at>UTC_TIMESTAMP(3) AS valid,
                               DATE_FORMAT(checked_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS created_text,
                               DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%s.%fZ') AS expires_text
                        FROM ds_capability_check WHERE id=?
                        """,
                        (row, index) -> new CheckRow(
                                row.getLong("id"),
                                row.getLong("actor_id"),
                                row.getObject("source_id", Long.class),
                                row.getObject("base_version", Integer.class),
                                row.getBytes("connection_fingerprint"),
                                row.getBytes("credential_fingerprint"),
                                row.getString("adapter_version"),
                                row.getString("policy_version"),
                                row.getString("created_text"),
                                row.getString("expires_text"),
                                row.getBoolean("valid"),
                                row.getString("results")),
                        id)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * 新源创建时只允许把尚未绑定的检查归到当前新源。
     */
    public boolean bindCheck(long id, long source) {
        return checks.update(Wrappers.<CapabilityCheckPO>lambdaUpdate()
                        .eq(CapabilityCheckPO::getId, id)
                        .isNull(CapabilityCheckPO::getSourceId)
                        .set(CapabilityCheckPO::getSourceId, source))
                == 1;
    }

    /**
     * 插入一条有自增编号的记录并取得平台数据库返回的主键。
     */
    private long insert(String statement, Object... parameters) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        sql.update(
                connection -> {
                    // 参数值以 JDBC 绑定，不将源库连接资料拼入平台 SQL。
                    var prepared = connection.prepareStatement(statement, Statement.RETURN_GENERATED_KEYS);
                    for (int index = 0; index < parameters.length; index++) {
                        prepared.setObject(index + 1, parameters[index]);
                    }
                    return prepared;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
}
