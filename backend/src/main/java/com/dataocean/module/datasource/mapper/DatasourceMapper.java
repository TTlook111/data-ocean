package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.entity.po.CapabilityCheckPO;
import com.dataocean.module.datasource.entity.po.DatasourceCredentialPO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.mapper.DatasourceConfigurationMapper.RevisionData;
import com.dataocean.module.datasource.mapper.DatasourceConfigurationMapper.RevisionSchema;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 保存数据源配置、秘密引用与分项测试证据。写事务由上层业务协调。
 */
@Repository
@RequiredArgsConstructor
public class DatasourceMapper {

    /**
     * 保存和回读连接配置修订及其 Schema。
     */
    private final DatasourceConfigurationMapper configurations;

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
        var schemas = configurations.schemas(source, revision);
        var row = configurations.revision(source, revision);
        if (row == null) {
            // 指定的配置修订可能不属于该来源，按原约定返回空结果。
            return null;
        }

        return revision(row, schemas);
    }

    /**
     * 读取同一来源下本页任务引用的不可变配置及各自的 Schema。
     */
    public Map<Long, RevisionRow> revisions(long source, List<Long> ids) {
        if (ids.isEmpty()) {
            // 空页无需读取历史配置。
            return Map.of();
        }

        var schemas = configurations.revisionSchemas(source, ids).stream()
                .collect(Collectors.groupingBy(
                        RevisionSchema::revisionId,
                        Collectors.mapping(RevisionSchema::schemaName, Collectors.toList())));
        return configurations.revisions(source, ids).stream()
                .collect(Collectors.toMap(
                        RevisionData::getId, data -> revision(data, schemas.getOrDefault(data.getId(), List.of()))));
    }

    /**
     * 由连接修订字段和已加载的 Schema 组装内部配置。
     */
    private RevisionRow revision(RevisionData row, List<String> schemas) {
        // 源连接参数和调度限制都来自同一持久化修订。
        var configuration = new SourceConfigurationDTO();
        configuration.setName(row.getSourceLabel());
        configuration.setDatabaseType(row.getDatabaseType());
        configuration.setHost(row.getHost());
        configuration.setPort(row.getPort());
        configuration.setDatabaseName(row.getDatabaseName());
        configuration.setAccount(row.getAccount());
        configuration.setPurpose(row.getPurpose());
        configuration.setTechnicalContactId(row.getTechnicalContactId());
        configuration.setBusinessContactId(row.getBusinessContactId());
        configuration.setSchemas(schemas);

        var runtime = new RuntimePolicyDTO();
        runtime.setMaxConnections(row.getMaxConnections());
        runtime.setCollectionConcurrency(row.getCollectionConcurrency());
        runtime.setRetryLimit(row.getRetryLimit());
        runtime.setTaskTimeoutSeconds(row.getTaskTimeoutSeconds());
        runtime.setQueryTimeoutSeconds(row.getQueryTimeoutSeconds());
        runtime.setRowLimit(row.getRowLimit());
        runtime.setSyncHours(row.getSyncHours());
        runtime.setWindowStart(
                LocalTime.ofSecondOfDay(row.getWindowStartMinute() * 60L).toString());
        runtime.setWindowEnd(
                LocalTime.ofSecondOfDay(row.getWindowEndMinute() * 60L).toString());
        return new RevisionRow(
                row.getId(),
                row.getRevision(),
                row.getCredentialRevisionId(),
                configuration,
                runtime,
                row.getScopeKey());
    }

    /**
     * 取得指定源、指定配置的已加密运行凭据。
     */
    public CredentialRow credential(long id, long revision) {
        return credentials.forConfiguration(id, revision);
    }

    /**
     * 写入新数据源身份，配置指针由同一事务在创建修订后补齐。
     */
    public long insertSource(String name, byte[] nameKey, String lifecycle, long actor) {
        // 配置修订需要使用数据库生成的数据源编号，缺少主键时终止本次创建事务。
        var source = new DatasourcePO();
        source.setName(name);
        source.setLifecycle(lifecycle);
        records.create(source, nameKey, actor);
        return Objects.requireNonNull(source.getId(), "数据库未返回数据源编号");
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
        // 按配置修订表绑定参数，运行窗口按距当日零点的分钟保存。
        var row = new DatasourceConfigurationMapper.RevisionData();
        row.setSourceId(id);
        row.setRevision(revision);
        row.setDatabaseType(config.getDatabaseType());
        row.setHost(config.getHost());
        row.setPort(config.getPort());
        row.setDatabaseName(config.getDatabaseName());
        row.setAccount(config.getAccount());
        row.setCredentialRevisionId(credential);
        row.setPurpose(config.getPurpose());
        row.setTechnicalContactId(config.getTechnicalContactId());
        row.setBusinessContactId(config.getBusinessContactId());
        row.setMaxConnections(limits.getMaxConnections());
        row.setCollectionConcurrency(limits.getCollectionConcurrency());
        row.setRetryLimit(limits.getRetryLimit());
        row.setTaskTimeoutSeconds(limits.getTaskTimeoutSeconds());
        row.setQueryTimeoutSeconds(limits.getQueryTimeoutSeconds());
        row.setRowLimit(limits.getRowLimit());
        row.setSyncHours(limits.getSyncHours());
        row.setWindowStartMinute(LocalTime.parse(limits.getWindowStart()).toSecondOfDay() / 60);
        row.setWindowEndMinute(LocalTime.parse(limits.getWindowEnd()).toSecondOfDay() / 60);
        row.setScopeKey(scopeKey);
        row.setCreatedBy(actor);
        configurations.create(row);
        long configurationId = Objects.requireNonNull(row.getId(), "数据库未返回配置修订编号");

        List<String> schemas = config.getSchemas();
        for (int index = 0; index < schemas.size(); index++) {
            // 原始 Schema 名作为来源身份，显示顺序不进入采集范围摘要。
            String schema = schemas.get(index);
            configurations.addSchema(configurationId, schema, schema.getBytes(StandardCharsets.UTF_8), index + 1);
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
        // 持久层生成检查编号，命令回执使用同一条插入返回的主键。
        var row = new CapabilityCheckPO();
        row.setSourceId(source);
        checks.create(
                row,
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
        return Objects.requireNonNull(row.getId(), "数据库未返回连接检查编号");
    }

    /**
     * 在平台数据库时间内判断测试是否过期。
     */
    public CheckRow check(long id) {
        return checks.evidence(id);
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
}
