package com.dataocean.module.datasource.service.impl;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.CommonException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.entity.dto.RuntimePolicyDTO;
import com.dataocean.module.datasource.entity.dto.ScheduleChangeDTO;
import com.dataocean.module.datasource.entity.dto.ScheduleProposalDTO;
import com.dataocean.module.datasource.entity.dto.SourceActionPreviewDTO;
import com.dataocean.module.datasource.entity.dto.SourceConfigurationDTO;
import com.dataocean.module.datasource.entity.dto.SourceEnableDTO;
import com.dataocean.module.datasource.entity.dto.SourceUpdateDTO;
import com.dataocean.module.datasource.entity.po.DatasourcePO;
import com.dataocean.module.datasource.entity.query.SourceQuery;
import com.dataocean.module.datasource.entity.vo.SourceDetailVO;
import com.dataocean.module.datasource.entity.vo.SourceRowVO;
import com.dataocean.module.datasource.mapper.CollectionScheduleMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.datasource.mapper.DatasourceMapper.CredentialRow;
import com.dataocean.module.datasource.mapper.DatasourceMapper.RevisionRow;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper;
import com.dataocean.module.datasource.mapper.DatasourcePageMapper.SourceSummary;
import com.dataocean.module.datasource.mapper.DatasourceRecordMapper;
import com.dataocean.module.datasource.mapper.SourceStateMapper;
import com.dataocean.module.datasource.service.DatasourceEvents;
import com.dataocean.module.datasource.service.DatasourceScheduler;
import com.dataocean.module.datasource.source.SourceSecretCodec;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
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

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 管理已存在的数据源、草稿启用和定时开关。
 */
@Service
@RequiredArgsConstructor
public class SourceManagement {

    /**
     * 平台来源及固定修订。
     */
    private final DatasourceMapper sources;

    /**
     * 列表、当前投影与定时状态。
     */
    private final SourceStateMapper state;

    /**
     * 获取来源在配置变更后的实际目录版本。
     */
    private final DatasourceRecordMapper records;

    /**
     * 读取数据源概况及同口径分页结果。
     */
    private final DatasourcePageMapper pages;

    /**
     * 切换计划时在同一事务重置接收游标。
     */
    private final CollectionScheduleMapper schedules;

    /**
     * 可真实查阅的定时推算。
     */
    private final DatasourceScheduler scheduler;

    /**
     * 数据源配置和测试证据的共用校验。
     */
    private final DatasourceServiceImpl connections;

    /**
     * 源秘密的受控加密。
     */
    private final SourceSecretCodec cipher;

    /**
     * 各页面及动作的实际权限。
     */
    private final PermissionService permissions;

    /**
     * 源及计划的核对令牌。
     */
    private final ReviewService reviews;

    /**
     * 事务内命令及审计检查。
     */
    private final MutationService commands;

    /**
     * 所有实质配置变更必须有审计。
     */
    private final AuditService audit;

    /**
     * 目录范围变化的同事务通知。
     */
    private final DatasourceEvents events;

    /**
     * 生成固定范围及秘密的摘要。
     */
    private final SecretKeyProvider signatures;

    /**
     * 核对提案使用有边界的 JSON 类型。
     */
    private final JsonCodec json;

    /**
     * 在真实数据库分页，列表不披露连接详情。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<SourceRowVO> list(SourceQuery query) {
        permissions.require("sources.view");

        String name = Objects.toString(query.getName(), "").strip();
        String type = Objects.toString(query.getDatabaseType(), "").strip();
        String contact =
                Objects.toString(query.getTechnicalContactKeyword(), "").strip();
        String lifecycle = Objects.toString(query.getLifecycle(), "").strip();

        // 只把白名单列交给分页插件，同值时由来源 ID 稳定排序。
        OrderItem order =
                switch (Objects.toString(query.getSortBy(), "")) {
                    case "" -> OrderItem.desc("s.updated_at");
                    case "name" -> OrderItem.asc("s.name").setAsc(Boolean.TRUE.equals(query.getIsAsc()));
                    case "updatedAt" -> OrderItem.asc("s.updated_at").setAsc(Boolean.TRUE.equals(query.getIsAsc()));
                    default -> throw BizIllegalException.invalid("不支持的排序字段");
                };

        // 由分页插件使用同一组条件执行 Mapper 中的专用计数语句。
        var page = query.<SourceSummary>toFixedOrderMpPage(order, OrderItem.desc("s.id"));
        page.setCountId("sourceCount");
        return PageVO.from(pages.sourcePage(page, name, type, contact, lifecycle), this::summary);
    }

    /**
     * 返回获准的目录或采集入口使用的来源选项。
     */
    public List<SourceOption> options() {
        return state.options().stream()
                // 将数据库 ID 转为接口字符串，保留页面所需的核对版本。
                .map(source -> new SourceOption(
                        Long.toString(source.getId()), source.getName(), source.getLifecycle(), source.getVersion()))
                .toList();
    }

    /**
     * 目录及采集入口可见的数据源选项。
     *
     * @param id 接口使用的来源 ID
     * @param name 当前名称
     * @param lifecycle 来源生命周期
     * @param version 核对使用的来源版本
     */
    public record SourceOption(String id, String name, String lifecycle, int version) {}

    /**
     * 显示当前配置、凭据是否已设置与实际计划。
     */
    @Transactional(readOnly = true)
    public SourceDetailVO detail(long id) {
        permissions.require("sources.view");
        DatasourcePO source = source(id, false);
        RevisionRow revision = sources.revision(id, source.getCurrentConnectionRevisionId());
        return new SourceDetailVO(
                summary(pages.sourceSummary(id)),
                revision.configuration(),
                revision.limits(),
                revision.credentialId() != null,
                revision.revision(),
                Objects.toString(source.getCurrentSnapshotId(), null),
                source.getActiveTaskId(),
                source.getPendingChangeId(),
                scheduler.status(source, revision, state.schedule(id)));
    }

    /**
     * 仅向有来源编辑资格者提供安全人员选项。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageVO<UserOptionVO> contacts(String keyword, PageQuery query) {
        permissions.requireAny("sources.create", "sources.edit");
        String text = Objects.toString(keyword, "").strip();
        return PageVO.from(
                state.contacts(text, query),
                user -> new UserOptionVO(
                        Long.toString(user.getId()),
                        user.getUsername(),
                        user.getDisplayName(),
                        user.getLifecycle(),
                        user.getVersion(),
                        true));
    }

    /**
     * 组装可披露的来源概况，将数据库 UTC 时间转换为接口时刻。
     */
    private SourceRowVO summary(SourceSummary row) {
        if (row == null) {
            // 缺少生效配置时，保留原来无概况的响应语义。
            return null;
        }
        return new SourceRowVO(
                Long.toString(row.id()),
                row.name(),
                row.databaseType(),
                row.detectedVersion(),
                row.technicalContact(),
                row.lifecycle(),
                row.latestState(),
                Times.instant(row.latestEnd()),
                Times.instant(row.committedAt()),
                row.version());
    }

    /**
     * 根据明确动作将安全提案绑定到当前来源。
     */
    public ImpactPreviewVO review(long id, SourceActionPreviewDTO input) {
        String action = input.getAction();
        DatasourcePO source = source(id, false);
        verifyVersion(source, input.getExpectedVersion());
        RevisionRow revision = sources.revision(id, source.getCurrentConnectionRevisionId());
        return switch (action) {
            case "UPDATE_CONFIG" -> {
                // 编辑核对使用完整非秘密配置。
                EditProposal proposal = json.mapper().treeToValue(input.getProposal(), EditProposal.class);
                validateEdit(source, revision, proposal);
                yield preview(
                        source,
                        action,
                        proposal,
                        "数据源配置",
                        revision.configuration().getName(),
                        proposal.configuration().getName());
            }
            case "ENABLE" -> {
                // 启用只依赖已保存草稿及测试依据。
                EnableProposal proposal = json.mapper().treeToValue(input.getProposal(), EnableProposal.class);
                requireDraft(source);
                yield preview(source, action, proposal, "配置状态", "草稿", "已启用");
            }
            case "PAUSE_SCHEDULE", "RESUME_SCHEDULE" -> {
                // 暂停说明保留在既有核对凭据和审计。
                ScheduleProposalDTO proposal =
                        json.mapper().treeToValue(input.getProposal(), ScheduleProposalDTO.class);
                validatePause(action, proposal);
                yield preview(
                        source,
                        action,
                        proposal,
                        "定时采集",
                        state.schedule(id).paused() ? "已暂停" : "运行中",
                        "PAUSE_SCHEDULE".equals(action) ? "已暂停" : "已恢复");
            }
            default -> throw BizIllegalException.invalid("无效的来源维护动作");
        };
    }

    /**
     * 保存实质配置变更及新的固定修订。
     */
    public MutationVO edit(long id, String command, SourceUpdateDTO input) {
        EditProposal proposal = new EditProposal(
                input.getConfiguration(), input.getRuntimePolicy(), input.isCredentialProvided(), input.getCheckId());
        String submitted = input.getPassword();
        String digest = submitted == null ? "" : HexFormat.of().formatHex(connections.passwordDigest(submitted));
        return commands.execute(
                "sources.edit",
                command,
                "datasource:" + id,
                List.of(input.getExpectedVersion(), proposal, digest),
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 当前配置和采集任务会同时变更，写前重新核对源版本。
                    DatasourcePO source = source(id, true);
                    verifyVersion(source, input.getExpectedVersion());
                    RevisionRow current = sources.revision(id, source.getCurrentConnectionRevisionId());
                    validateEdit(source, current, proposal);
                    reviews.verify(
                            "UPDATE_CONFIG", Long.toString(id), source.getVersion(), proposal, input.getReviewToken());
                    boolean hasPassword = submitted != null && !submitted.isEmpty();
                    if (hasPassword != proposal.credentialProvided()) {
                        throw BizIllegalException.invalid("凭据输入与核对内容不一致");
                    }
                    SourceConfigurationDTO config = proposal.configuration();
                    RuntimePolicyDTO runtime = proposal.runtimePolicy();
                    boolean scopeChanged = !new HashSet<>(config.getSchemas())
                                    .equals(new HashSet<>(
                                            current.configuration().getSchemas()))
                            || !Objects.equals(
                                    config.getDatabaseName(),
                                    current.configuration().getDatabaseName())
                            || !Objects.equals(
                                    config.getDatabaseType(),
                                    current.configuration().getDatabaseType());
                    if (!hasPassword
                            && equivalent(config, current.configuration())
                            && runtime.equals(current.limits())) {
                        return result(source, command, source.getVersion());
                    }
                    Long credential = current.credentialId();
                    if (hasPassword) {
                        // 启用源更新凭据必须走专门的停止和替换路径。
                        String key = cipher.version();
                        credential = sources.insertCredential(
                                id,
                                UserContext.getUser(),
                                new CredentialRow(key, cipher.encrypt(submitted, connections.context(id), key)));
                    }
                    if ("ENABLED".equals(source.getLifecycle())
                            && (config.getSchemas().stream().anyMatch(schema -> !current.configuration()
                                            .getSchemas()
                                            .contains(schema))
                                    || runtime.getQueryTimeoutSeconds()
                                            != current.limits().getQueryTimeoutSeconds())) {
                        connections.verifySavedCheck(
                                proposal.checkId(), source, config, runtime, storedPassword(source));
                    }
                    if (source.getActiveTaskId() != null && scopeChanged) {
                        throw BizIllegalException.state("采集期间请等待任务结束再调整范围");
                    }
                    byte[] scope = signatures.hmac("source-scope|"
                            + json.write(List.of(
                                    Objects.toString(config.getDatabaseType(), ""),
                                    Objects.toString(config.getDatabaseName(), ""),
                                    config.getSchemas().stream().sorted().toList())));
                    long revision = sources.insertConfiguration(
                            id, UserContext.getUser(), current.revision() + 1, credential, config, runtime, scope);
                    boolean scheduleChanged = runtime.getSyncHours()
                                    != current.limits().getSyncHours()
                            || !runtime.getWindowStart().equals(current.limits().getWindowStart())
                            || !runtime.getWindowEnd().equals(current.limits().getWindowEnd());
                    try {
                        // 同名并发由平台唯一键裁定。
                        String name = config.getName().strip();
                        records.configure(
                                id,
                                revision,
                                name,
                                Normalizer.normalize(name, Normalizer.Form.NFKC)
                                        .toLowerCase(Locale.ROOT)
                                        .getBytes(StandardCharsets.UTF_8),
                                scopeChanged);
                        if (scheduleChanged) {
                            // 新调度修订重新建立时点，旧周期不能再次接收。
                            schedules.reset(id);
                        }
                    } catch (DuplicateKeyException error) {
                        throw new CommonException(409501, 409, "数据源名称已存在", error);
                    }
                    if (scopeChanged) {
                        events.catalog(id, source.getCurrentSnapshotId(), records.catalogVersion(id));
                    }
                    record(
                            source,
                            "sources.edit",
                            command,
                            null,
                            "revision",
                            "配置修订",
                            Integer.toString(current.revision()),
                            Integer.toString(current.revision() + 1));
                    return result(source, command, source.getVersion() + 1);
                });
    }

    /**
     * 用新的真实测试核对启用已有草稿，保留其配置修订号。
     */
    public MutationVO enable(long id, String command, SourceEnableDTO input) {
        var proposal = new EnableProposal(input.getCheckId());
        return commands.execute(
                "sources.enable",
                command,
                "datasource:" + id,
                List.of(input.getExpectedVersion(), proposal),
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 启用只切换生命周期。
                    DatasourcePO source = source(id, true);
                    verifyVersion(source, input.getExpectedVersion());
                    requireDraft(source);
                    reviews.verify("ENABLE", Long.toString(id), source.getVersion(), proposal, input.getReviewToken());
                    RevisionRow revision = sources.revision(id, source.getCurrentConnectionRevisionId());
                    connections.verifySavedCheck(
                            proposal.checkId(),
                            source,
                            revision.configuration(),
                            revision.limits(),
                            storedPassword(source));
                    state.lifecycle(id, "ENABLED");
                    record(source, "sources.enable", command, null, "lifecycle", "配置状态", "DRAFT", "ENABLED");
                    return result(source, command, source.getVersion() + 1);
                });
    }

    /**
     * 更新后续定时开关，实际运行中的任务继续其原流程。
     */
    public SourceDetailVO pause(long id, String command, ScheduleChangeDTO input) {
        String action = input.getAction();
        validatePause(action, input.getProposal());
        commands.execute(
                "sources.pause",
                command,
                "datasource:" + id,
                List.of(input.getExpectedVersion(), action, input.getProposal()),
                input.getExpectedVersion(),
                false,
                false,
                () -> {
                    // 暂停／恢复回执在同一来源版本内提交。
                    DatasourcePO source = source(id, true);
                    verifyVersion(source, input.getExpectedVersion());
                    reviews.verify(
                            action,
                            Long.toString(id),
                            source.getVersion(),
                            input.getProposal(),
                            input.getReviewToken());
                    boolean paused = "PAUSE_SCHEDULE".equals(action);
                    var prior = state.schedule(id);
                    if (paused == prior.paused()) {
                        return result(source, command, source.getVersion());
                    }
                    state.pause(id, paused);
                    if (prior.anchor() != null) {
                        state.cursor(id, prior.lastCycle(), null);
                    }
                    record(
                            source,
                            "sources.pause",
                            command,
                            input.getProposal().getReason(),
                            "schedulePaused",
                            "定时暂停",
                            Boolean.toString(prior.paused()),
                            Boolean.toString(paused));
                    return result(source, command, source.getVersion() + 1);
                });
        return detail(id);
    }

    /**
     * 检查已填写的范围及是否存在必须走替换流程的配置变化。
     */
    private void validateEdit(DatasourcePO source, RevisionRow current, EditProposal proposal) {
        if (source.getPendingChangeId() != null || "RETIRED".equals(source.getLifecycle())) {
            throw BizIllegalException.state("当前源不能修改配置");
        }
        SourceConfigurationDTO config = proposal.configuration();
        if (config == null
                || proposal.runtimePolicy() == null
                || config.getName() == null
                || config.getName().isBlank()) {
            throw BizIllegalException.invalid("请填写数据源名称和配置");
        }
        connections.validateLimits(config, proposal.runtimePolicy());
        SourceConfigurationDTO prior = current.configuration();
        if (!"DRAFT".equals(source.getLifecycle())) {
            if (!List.of(
                            Objects.toString(prior.getDatabaseType(), ""),
                            Objects.toString(prior.getHost(), ""),
                            Objects.toString(prior.getPort(), ""),
                            Objects.toString(prior.getDatabaseName(), ""))
                    .equals(List.of(
                            Objects.toString(config.getDatabaseType(), ""),
                            Objects.toString(config.getHost(), ""),
                            Objects.toString(config.getPort(), ""),
                            Objects.toString(config.getDatabaseName(), "")))) {
                throw new CommonException(409505, 409, "数据库身份改变时请新建数据源", null);
            }
            if (proposal.credentialProvided() || !Objects.equals(prior.getAccount(), config.getAccount())) {
                throw BizIllegalException.state("已启用源请从更换凭据入口修改账号或密码");
            }
        }
        validateContact(config.getTechnicalContactId(), prior.getTechnicalContactId());
        validateContact(config.getBusinessContactId(), prior.getBusinessContactId());
    }

    /**
     * 归档联系人可历史回看，新联系人必须处于可选范围。
     */
    private void validateContact(String selected, String former) {
        Long id = Ids.nullable(selected);
        if (id != null && !Objects.equals(selected, former) && !state.validContact(id)) {
            throw BizIllegalException.invalid("选中的联系人当前不可用");
        }
    }

    /**
     * 只允许草稿走单独的启用核对。
     */
    private void requireDraft(DatasourcePO source) {
        if (!"DRAFT".equals(source.getLifecycle())) {
            throw BizIllegalException.state("当前源不是可启用的草稿");
        }
    }

    /**
     * 明确暂停原因，恢复时保留可选说明。
     */
    private void validatePause(String action, ScheduleProposalDTO proposal) {
        String reason = Objects.toString(proposal.getReason(), "").strip();
        if (("PAUSE_SCHEDULE".equals(action) && reason.isEmpty()) || reason.codePointCount(0, reason.length()) > 500) {
            throw BizIllegalException.invalid("暂停时请填写 1 至 500 字的原因");
        }
    }

    /**
     * 有效配置比较忽略 Schema 的展示排列顺序。
     */
    private boolean equivalent(SourceConfigurationDTO left, SourceConfigurationDTO right) {
        if (!new HashSet<>(left.getSchemas()).equals(new HashSet<>(right.getSchemas()))) {
            return false;
        }
        // 用类型化复制的 JSON 对象移除各自只影响展示的 Schema 顺序。
        var leftNode = json.mapper().valueToTree(left);
        var rightNode = json.mapper().valueToTree(right);
        ((tools.jackson.databind.node.ObjectNode) leftNode).remove("schemas");
        ((tools.jackson.databind.node.ObjectNode) rightNode).remove("schemas");
        return leftNode.equals(rightNode);
    }

    /**
     * 取得当前源已加密的运行凭据。
     */
    String storedPassword(DatasourcePO source) {
        var secret = sources.credential(source.getId(), source.getCurrentConnectionRevisionId());
        return secret == null
                ? null
                : cipher.decrypt(secret.ciphertext(), connections.context(source.getId()), secret.keyVersion());
    }

    /**
     * 以准确的来源版本签发短时核对。
     */
    private ImpactPreviewVO preview(
            DatasourcePO source, String action, Object proposal, String label, String before, String after) {
        return reviews.preview(
                action,
                Long.toString(source.getId()),
                source.getVersion(),
                proposal,
                List.of(new ChangeVO("configuration", label, before, after)),
                List.of(),
                List.of());
    }

    /**
     * 获取来源行及必要排他锁。
     */
    DatasourcePO source(long id, boolean lock) {
        DatasourcePO source = lock ? sources.lock(id) : sources.get(id);
        if (source == null) {
            throw BizIllegalException.missing();
        }
        return source;
    }

    /**
     * 配置写入必须使用最新源版本。
     */
    void verifyVersion(DatasourcePO source, Integer requested) {
        if (!Objects.equals(requested, source.getVersion())) {
            throw BizIllegalException.conflict();
        }
    }

    /**
     * 保存与命令同事务的操作审计。
     */
    void record(
            DatasourcePO source,
            String action,
            String command,
            String reason,
            String field,
            String label,
            String before,
            String after) {
        audit.record(AuditWriteDTO.builder()
                .actionCode(action)
                .targetType("datasource")
                .targetId(Long.toString(source.getId()))
                .targetName(source.getName())
                .reason(reason)
                .changes(List.of(new ChangeVO(field, label, before, after)))
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(UUID.fromString(command).toString())
                .build());
    }

    /**
     * 共用审计写入，核实事实使用实际变化标识而非来源标识。
     */
    void recordStructureChange(
            DatasourcePO source, String id, String command, String reason, String before, String decision) {
        audit.record(AuditWriteDTO.builder()
                .actionCode("collections.review")
                .targetType("structure_change")
                .targetId(id)
                .targetName(source.getName())
                .reason(reason)
                .changes(List.of(new ChangeVO("decision", "核实结论", before, decision)))
                .beforeRoles(List.of())
                .afterRoles(List.of())
                .securityVersion(commands.auditSecurityVersion(false))
                .commandId(UUID.fromString(command).toString())
                .build());
    }

    /**
     * 公共命令返回资源引用。
     */
    private MutationVO result(DatasourcePO source, String command, int version) {
        return new MutationVO(command, "datasource", Long.toString(source.getId()), version);
    }

    /**
     * 编辑时核对的非秘密配置。
     *
     * @param configuration 复用已声明的连接配置
     * @param runtimePolicy 复用运行参数
     * @param credentialProvided 是否提交秘密
     * @param checkId 明确的测试依据
     */
    private record EditProposal(
            SourceConfigurationDTO configuration,
            RuntimePolicyDTO runtimePolicy,
            boolean credentialProvided,
            String checkId) {}

    /**
     * 启用仅核对选中的有效测试。
     *
     * @param checkId 已完成的测试
     */
    private record EnableProposal(String checkId) {}
}
