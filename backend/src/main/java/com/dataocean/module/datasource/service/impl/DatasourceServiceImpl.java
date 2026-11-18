package com.dataocean.module.datasource.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.CommonException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.dto.ConnectionCheckDTO;
import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateProposalDTO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.entity.vo.ConnectionCheckVO;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CheckRow;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CredentialRow;
import com.dataocean.module.datasource.service.DatasourceService;
import com.dataocean.module.datasource.source.SourceConnection;
import com.dataocean.module.datasource.source.SourceConnection.Type;
import com.dataocean.module.datasource.source.SourceConnectionProperties;
import com.dataocean.module.datasource.source.SourceProbe;
import com.dataocean.module.datasource.source.SourceProbe.Report;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 组织源连接测试、配置核对及同事务数据源建档。
 */
@Service
@RequiredArgsConstructor
public class DatasourceServiceImpl implements DatasourceService {

    /**
     * 平台数据源配置和检查证据。
     */
    private final DatasourceMapper mapper;

    /**
     * 执行与实际采集同策略的连接检查。
     */
    private final SourceProbe probe;

    /**
     * 约束配置可用的目标与测试策略版本。
     */
    private final SourceConnectionProperties policy;

    /**
     * 保护来源连接配置的账号密码。
     */
    private final SourceSecretCodec cipher;

    /**
     * 公共 HMAC 用于检查证据与请求的保密关联。
     */
    private final SecretKeyProvider signatures;

    /**
     * 读取或保存明确类型的能力报告。
     */
    private final JsonCodec json;

    /**
     * 当前会话实际拥有的功能和页面权限。
     */
    private final PermissionService permissions;

    /**
     * 与 IAM 复用的短时核对凭据服务。
     */
    private final ReviewService reviews;

    /**
     * 与业务动作同事务的命令防重服务。
     */
    private final MutationService commands;

    /**
     * 分项检查和原命令回执一起保存，网络探测期间不持有平台事务。
     */
    private final CommandMapper commandRecords;

    /**
     * 写入探测结果时重核授权，避免源库访问期间的撤权造成越权回执。
     */
    private final GuardMapper guard;

    /**
     * 独立的检查结果保存事务。
     */
    private final TransactionTemplate transactions;

    /**
     * 保存来源配置变更的成功审计。
     */
    private final AuditService audit;

    /**
     * 限制同一实例并发等待源库连接的探测数。
     */
    private final Semaphore probeSlots = new Semaphore(4);

    /**
     * 当前只读适配检查语义版本。
     */
    private static final String ADAPTER_VERSION = "1";

    /**
     * 从当前请求、已保存版本和实际权限建立测试依据。
     */
    @Override
    public ConnectionCheckVO test(Long sourceId, String command, ConnectionCheckDTO input) {
        permissions.require("sources.test");
        long actor = UserContext.getUser();
        String key = command == null
                ? UUID.randomUUID().toString()
                : UUID.fromString(command).toString();
        byte[] request = signatures.hmac("source-probe-request|"
                + json.write(List.of(
                        Objects.toString(sourceId, ""),
                        input,
                        HexFormat.of().formatHex(passwordDigest(input.getPassword())))));
        ConnectionCheckVO replay = previousCheck(actor, key, request);
        if (replay != null) {
            return replay;
        }
        DatasourcePO source = sourceId == null ? null : requireSource(sourceId);
        String password = input.getPassword();
        if (source != null) {
            // 已保存源的测试必须核对当前版本，用空密码表示复用原凭据。
            if (!Objects.equals(input.getExpectedVersion(), source.getVersion())) {
                throw new BizIllegalException(ErrorCode.VERSION_CONFLICT, "数据源配置已变化，请刷新后测试");
            }
            if (password == null || password.isEmpty()) {
                CredentialRow stored = mapper.credential(source.getId(), source.getCurrentConnectionRevisionId());
                password = stored == null
                        ? null
                        : cipher.decrypt(stored.ciphertext(), context(source.getId()), stored.keyVersion());
            }
        }

        SourceConnection connection = connection(input.getConfiguration(), input.getRuntimePolicy(), password, false);
        if (!probeSlots.tryAcquire()) {
            // 探测名额耗尽时调用方可稍后重试，避免排队造成未知状态。
            throw new CommonException(429501, 429, "连接测试繁忙，请稍后重试", null);
        }
        Report report;
        try {
            // 源库网络调用保持在任何平台写事务之外。
            report = probe.check(connection, password);
        } finally {
            // 始终释放连接测试名额。
            probeSlots.release();
        }
        byte[] secretDigest = passwordDigest(password);
        try {
            return transactions.execute(status -> {
                // 按同一命令原子保存报告、回执和当前可信的读取资格。
                guard.shared();
                permissions.require("sources.test");
                commandRecords.insert(actor, "sources.test", key, request);
                if (source != null && !Objects.equals(mapper.lock(sourceId).getVersion(), input.getExpectedVersion())) {
                    throw BizIllegalException.conflict();
                }
                long id = mapper.insertCheck(
                        sourceId,
                        actor,
                        input.getExpectedVersion(),
                        connectionDigest(connection),
                        secretDigest,
                        ADAPTER_VERSION,
                        policy.getPolicyVersion(),
                        report.detectedVersion(),
                        json.write(report.verifiedSchemas()),
                        json.write(report.structureObjectRefs()),
                        json.write(report),
                        report.readOnlySafety(),
                        report.businessReadStatus());
                commandRecords.complete(
                        actor,
                        "sources.test",
                        key,
                        json.write(new MutationVO(key, "connection_check", Long.toString(id), 1)));
                return check(id);
            });
        } catch (DuplicateKeyException competing) {
            // 并发相同命令只回读已经提交的真实报告，不创建第二条检查记录。
            ConnectionCheckVO result = previousCheck(actor, key, request);
            if (result == null) {
                throw competing;
            }
            return result;
        }
    }

    /**
     * 回读原命令，密码摘要和实际测试目标不一致时恢复原操作而非重用。
     */
    private ConnectionCheckVO previousCheck(long actor, String key, byte[] request) {
        var existing = commandRecords.find(Long.toString(actor), "sources.test", key);
        if (existing == null) {
            return null;
        }
        if (!signatures.matches(existing.getPayloadHmac(), request)) {
            throw new BizIllegalException(ErrorCode.COMMAND_CONTENT_CONFLICT, "同一连接测试编号的内容不同");
        }
        if (!"COMMITTED".equals(existing.getState())) {
            throw new BizIllegalException(ErrorCode.COMMAND_IN_PROGRESS, "原连接测试仍在处理");
        }
        return check(
                Ids.parse(json.read(existing.getResultRef(), MutationVO.class).getTargetId()));
    }

    /**
     * 未绑定的连接测试仅原操作者可读，已绑定源使用数据源页面权限判定。
     */
    @Override
    public ConnectionCheckVO check(long id) {
        permissions.require("sources.test");
        CheckRow check = mapper.check(id);
        if (check == null
                || (check.sourceId() == null
                        && check.actorId() != UserContext.current().userId())) {
            // 不披露其他操作者的未保存源测试结果。
            throw BizIllegalException.missing();
        }
        Report report = json.read(check.report(), Report.class);
        return new ConnectionCheckVO(
                Long.toString(check.id()),
                check.sourceId() == null ? null : Long.toString(check.sourceId()),
                check.created(),
                check.expires(),
                report.detectedVersion(),
                report.availableSchemas(),
                report.verifiedSchemas(),
                report.structureObjectRefs(),
                report.items(),
                report.readOnlySafety(),
                report.businessReadStatus(),
                check.valid() && report.canEnable());
    }

    /**
     * 新建源核对只接受非秘密提案，并按目标状态提前复核权限。
     */
    @Override
    public ImpactPreviewVO reviewCreate(SourceCreateProposalDTO proposal) {
        validateProposal(proposal);
        var configuration = proposal.getConfiguration();
        return reviews.preview(
                "sources.create",
                "new",
                null,
                proposal,
                List.of(
                        new ChangeVO("name", "数据源名称", null, configuration.getName()),
                        new ChangeVO("databaseName", "数据库", null, configuration.getDatabaseName()),
                        new ChangeVO("account", "数据库只读账号", null, configuration.getAccount()),
                        new ChangeVO("lifecycle", "配置状态", null, proposal.getTargetLifecycle())),
                List.of(),
                List.of());
    }

    /**
     * 在原有命令与审计协议下保存配置、密文和测试关联。
     */
    @Override
    public MutationVO create(String command, SourceCreateDTO input) {
        SourceCreateProposalDTO proposal = input.getProposal();
        String password = input.getPassword();
        String passwordFingerprint = password == null ? "" : HexFormat.of().formatHex(passwordDigest(password));
        return commands.execute(
                "sources.create",
                command,
                "datasource:new",
                List.of(proposal, passwordFingerprint),
                null,
                false,
                false,
                () -> {
                    // 事务内再次核对当前资格、提案、有效检查与凭据绑定。
                    validateProposal(proposal);
                    reviews.verify("sources.create", "new", null, proposal, input.getReviewToken());
                    if (proposal.isCredentialProvided() != (password != null && !password.isEmpty())) {
                        throw BizIllegalException.invalid("密码输入与核对内容不一致");
                    }
                    return storeNew(command, proposal, password);
                });
    }

    /**
     * 新增前核对名称及已填写的范围和运行设置。
     */
    private void validateProposal(SourceCreateProposalDTO proposal) {
        SourceConfigurationDTO config = proposal.getConfiguration();
        if (config.getName() == null || config.getName().strip().isEmpty()) {
            // 草稿也需要一个可识别的数据源名称。
            throw BizIllegalException.invalid("请填写数据源名称");
        }
        validateLimits(config, proposal.getRuntimePolicy());
        if ("ENABLED".equals(proposal.getTargetLifecycle())) {
            // 启用必须同时具备启用权限和真实连接测试编号。
            permissions.require("sources.enable");
            if (!proposal.isCredentialProvided() || proposal.getCheckId() == null) {
                throw new CommonException(409503, 409, "请先完成连接测试", null);
            }
        }
    }

    /**
     * 执行新建及初始修订写入，审计仅记录真实业务变化。
     */
    private MutationVO storeNew(String command, SourceCreateProposalDTO proposal, String password) {
        SourceConfigurationDTO config = proposal.getConfiguration();
        CheckRow check = null;
        if (proposal.getCheckId() != null) {
            // 只绑定原操作者尚未使用的有效检查。
            check = validatedCheck(
                    proposal.getCheckId(),
                    connection(
                            config,
                            proposal.getRuntimePolicy(),
                            password,
                            "ENABLED".equals(proposal.getTargetLifecycle())),
                    password);
        }
        long actor = UserContext.current().userId();
        String name = config.getName().strip();
        byte[] nameKey = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        if (nameKey.length > 1280) {
            // 核对规范化名称实际所需存储空间。
            throw BizIllegalException.invalid("数据源名称过长");
        }
        long source;
        try {
            source = mapper.insertSource(name, nameKey, proposal.getTargetLifecycle(), actor);
        } catch (DuplicateKeyException exception) {
            // 由唯一约束裁定并发同名新增。
            throw new CommonException(409501, 409, "数据源名称已存在", exception);
        }
        Long credentialId = null;
        if (proposal.isCredentialProvided()) {
            // 只保存当前所需的认证密文，密钥文件不进入数据库。
            String version = cipher.version();
            credentialId = mapper.insertCredential(
                    source, actor, new CredentialRow(version, cipher.encrypt(password, context(source), version)));
        }
        byte[] scope = signatures.hmac("source-scope|"
                + json.write(List.of(
                        Objects.toString(config.getDatabaseType(), ""),
                        Objects.toString(config.getDatabaseName(), ""),
                        config.getSchemas().stream().sorted().toList())));
        long revision =
                mapper.insertConfiguration(source, actor, 1, credentialId, config, proposal.getRuntimePolicy(), scope);
        mapper.initialConfiguration(source, revision);
        if (check != null && !mapper.bindCheck(check.id(), source)) {
            // 另一笔事务已经使用该检查，整笔新增回滚。
            throw staleCheck();
        }
        audit.record(AuditWriteDTO.builder()
                .actionCode("sources.create")
                .targetType("datasource")
                .targetId(Long.toString(source))
                .targetName(name)
                .changes(List.of(new ChangeVO("lifecycle", "配置状态", null, proposal.getTargetLifecycle())))
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(command)
                .build());
        return new MutationVO(command, "datasource", Long.toString(source), 1);
    }

    /**
     * 审核真实检查的主体、绑定、范围以及密码，禁止拿其他参数的报告启用来源。
     */
    private CheckRow validatedCheck(String id, SourceConnection target, String password) {
        CheckRow check = mapper.check(Ids.parse(id));
        if (check == null
                || check.actorId() != UserContext.current().userId()
                || check.sourceId() != null
                || !check.valid()
                || !policy.getPolicyVersion().equals(check.policyVersion())
                || !ADAPTER_VERSION.equals(check.adapterVersion())
                || !signatures.matches(check.configurationFingerprint(), connectionDigest(target))
                || !signatures.matches(check.credentialFingerprint(), passwordDigest(password))) {
            // 过期、不同目标或他人的检查结果不能用于本次新增。
            throw staleCheck();
        }
        Report report = json.read(check.report(), Report.class);
        if (!report.canEnable() || !report.verifiedSchemas().containsAll(target.schemas())) {
            // 业务行未验证不拦截启用，安全与结构必须实际达到条件。
            throw new CommonException(409503, 409, "本次测试未满足启用条件，请核对配置后重新测试", null);
        }
        return check;
    }

    /**
     * 检查已填写的范围，不要求草稿补齐连接资料。
     */
    void validateLimits(SourceConfigurationDTO configuration, RuntimePolicyDTO limits) {
        List<String> schemas = configuration.getSchemas();
        if (schemas == null
                || schemas.stream().anyMatch(schema -> schema == null || schema.isBlank() || schema.length() > 160)
                || new HashSet<>(schemas).size() != schemas.size()
                || ("MYSQL".equals(configuration.getDatabaseType()) && !schemas.isEmpty())) {
            // MySQL 不能接收 Schema 选择，PostgreSQL 使用实际集合校验。
            throw BizIllegalException.invalid("Schema 范围无效");
        }
        if (limits.getCollectionConcurrency() > limits.getMaxConnections()
                || limits.getWindowStart().equals(limits.getWindowEnd())) {
            // 并发上限和时间窗口必须符合组合约束。
            throw BizIllegalException.invalid("采集并发或采集时段配置无效");
        }
    }

    /**
     * 建立用于真实连接的固定参数，并拒绝缺少必要信息的请求。
     */
    SourceConnection connection(
            SourceConfigurationDTO config, RuntimePolicyDTO limits, String password, boolean enable) {
        validateLimits(config, limits);
        if (List.of(
                                Objects.toString(config.getDatabaseType(), ""),
                                Objects.toString(config.getHost(), ""),
                                Objects.toString(config.getDatabaseName(), ""),
                                Objects.toString(config.getAccount(), ""))
                        .stream()
                        .anyMatch(String::isBlank)
                || config.getPort() == null
                || password == null
                || password.isEmpty()) {
            // 不把不完整的配置送入源库驱动。
            throw BizIllegalException.invalid("请补齐数据库类型、连接地址、端口、数据库、只读账号和密码");
        }
        Type type = Type.valueOf(config.getDatabaseType());
        if (enable && type == Type.POSTGRESQL && config.getSchemas().isEmpty()) {
            // PostgreSQL 必须明确选择本次接入的数据范围。
            throw BizIllegalException.invalid("至少选择一个已验证的 Schema");
        }
        return new SourceConnection(
                type,
                config.getHost(),
                config.getPort(),
                config.getDatabaseName(),
                config.getSchemas(),
                config.getAccount(),
                limits.getQueryTimeoutSeconds());
    }

    /**
     * 绑定实际连接参数而允许在已验证集合内收窄 Schema。
     */
    private byte[] connectionDigest(SourceConnection source) {
        return signatures.hmac("source-connection|"
                + json.write(List.of(
                        source.type(),
                        source.host(),
                        source.port(),
                        source.database(),
                        source.account(),
                        source.queryTimeoutSeconds())));
    }

    /**
     * 对密码原样计算用途独立的 HMAC，不做 trim 或归一化。
     */
    byte[] passwordDigest(String password) {
        return signatures.hmac("source-password|" + password);
    }

    /**
     * 归属逻辑固定到稳定数据源，密文不能在不同源之间搬用。
     */
    String context(long source) {
        return "datasource:credential:" + source;
    }

    /**
     * 启用、编辑和凭据替换共用已保存源测试的实际关联条件。
     */
    void verifySavedCheck(
            String id, DatasourcePO source, SourceConfigurationDTO config, RuntimePolicyDTO limits, String password) {
        if (id == null) {
            throw new CommonException(409503, 409, "请先完成连接测试", null);
        }
        var connection = connection(config, limits, password, true);
        CheckRow check = mapper.check(Ids.parse(id));
        if (check == null
                || !Objects.equals(check.sourceId(), source.getId())
                || !check.valid()
                || (check.baseVersion() != null && !Objects.equals(check.baseVersion(), source.getVersion()))
                || !policy.getPolicyVersion().equals(check.policyVersion())
                || !ADAPTER_VERSION.equals(check.adapterVersion())
                || !signatures.matches(check.configurationFingerprint(), connectionDigest(connection))
                || !signatures.matches(check.credentialFingerprint(), passwordDigest(password))) {
            // 主体、配置版本、当前平台策略和秘密依据均须匹配。
            throw staleCheck();
        }
        Report report = json.read(check.report(), Report.class);
        if (!report.canEnable() || !report.verifiedSchemas().containsAll(connection.schemas())) {
            throw new CommonException(409503, 409, "连接测试未覆盖当前启用条件", null);
        }
    }

    /**
     * 从平台库读取源资料，缺少时使用公共不可访问响应。
     */
    private DatasourcePO requireSource(long source) {
        DatasourcePO stored = mapper.get(source);
        if (stored == null) {
            throw BizIllegalException.missing();
        }
        return stored;
    }

    /**
     * 使用模块规定的测试过期或不匹配响应。
     */
    private CommonException staleCheck() {
        return new CommonException(409502, 409, "连接测试已失效，请重新测试", null);
    }
}
