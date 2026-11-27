package com.dataocean.module.model.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.ErrorVO.FieldErrorVO;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.exception.FieldValidationException;
import com.dataocean.infrastructure.CredentialProtection;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.entity.dto.ModelAssignmentDTO;
import com.dataocean.module.model.entity.dto.ModelSaveDTO;
import com.dataocean.module.model.entity.dto.ModelStateDTO;
import com.dataocean.module.model.entity.vo.ModelConfigVO;
import com.dataocean.module.model.entity.vo.ModelNodeVO;
import com.dataocean.module.model.mapper.ModelMapper;
import com.dataocean.module.model.mapper.ModelMapper.Config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 管理后台配置、逐节点选择及当次受控模型访问。
 */
@Service
@RequiredArgsConstructor
public class ModelRegistry {

    /**
     * 表说明生成的正式业务节点标识。
     */
    public static final String TABLE_DESCRIPTION = "table-description";

    /**
     * 字段解释 Agent 的独立模型分配。
     */
    public static final String FIELD_EXPLANATION = "field-explanation";

    /**
     * 关联候选发现的独立模型分配。
     */
    public static final String RELATION_DISCOVERY = "relation-discovery";

    /**
     * 指标候选发现的独立模型分配。
     */
    public static final String METRIC_DISCOVERY = "metric-discovery";

    /**
     * 基于所选源建议文档范围的独立知识节点。
     */
    public static final String DOCUMENT_PLAN = "document-plan";

    /**
     * 使用核对后的治理事实生成正文或既有文档差异。
     */
    public static final String DOCUMENT_WRITE = "document-write";

    /**
     * 根据确切知识确认准备标准题草稿的独立生成节点。
     */
    public static final String QUESTION_SUGGESTION = "question-suggestion";

    /**
     * 只处理近期窗口之外的问数历史。
     */
    public static final String HISTORY_SUMMARY = "history-summary";

    /**
     * 只对已通过复核的问数候选排序。
     */
    public static final String RAG_RERANK = "rag-rerank";

    /**
     * 对已获准的反馈事项原问进行同源相似候选排序。
     */
    public static final String FEEDBACK_SIMILARITY = "feedback-similarity";

    /**
     * 与正式业务适配器中当前可发出模型请求的节点对应，目录中的预配节点也可单独回看。
     */
    private static final Set<String> CONNECTED_NODES = Set.of(
            TABLE_DESCRIPTION,
            FIELD_EXPLANATION,
            RELATION_DISCOVERY,
            METRIC_DISCOVERY,
            DOCUMENT_PLAN,
            DOCUMENT_WRITE,
            QUESTION_SUGGESTION,
            HISTORY_SUMMARY,
            RAG_RERANK,
            FEEDBACK_SIMILARITY,
            "intent",
            "sql-generate",
            "sql-semantic-check",
            "output-meaning",
            "sql-repair",
            "result-explanation",
            "rag-embedding");

    /**
     * 平台配置和调用诊断的查询入口。
     */
    private final ModelMapper models;

    /**
     * 复用版本化凭据保护，模型使用独立认证上下文。
     */
    private final CredentialProtection credentials;

    /**
     * 替换凭据沿用已有的细粒度权限。
     */
    private final PermissionService permissions;

    /**
     * 企业部署允许发送模型请求的 HTTPS 主机，逗号分隔。
     */
    @Value("${dataocean.models.allowed-hosts:}")
    private String allowedHosts;

    /**
     * 读取当前后台配置列表。
     */
    public PageVO<ModelConfigVO> list(PageQuery page, String keyword) {
        return PageVO.from(models.list(page.toFixedOrderMpPage(), keyword), ModelRegistry::view);
    }

    /**
     * 列出产品定义的节点、所需能力及当前选择。
     */
    public List<ModelNodeVO> nodes() {
        return models.assignments().stream()
                .map(row -> new ModelNodeVO(
                        row.nodeCode(),
                        row.capability(),
                        Objects.toString(row.modelId(), null),
                        row.version(),
                        CONNECTED_NODES.contains(row.nodeCode())))
                .toList();
    }

    /**
     * 读取全部可披露的模型选项，节点选择独立于列表分页。
     */
    public List<ModelConfigVO> options() {
        return models.options().stream().map(ModelRegistry::view).toList();
    }

    /**
     * 一次事务保存配置修订和必要的凭据变更。
     */
    @Transactional
    public ModelConfigVO save(Long modelId, ModelSaveDTO input) {
        String endpoint = checkedEndpoint(input.getEndpoint());
        boolean needsCredential =
                input.getCredential() != null && !input.getCredential().isBlank();
        long revisionId = IdWorker.getId();
        Config current = null;
        long id;
        if (modelId == null) {
            // 新模型必须完整提供访问凭据，首次保存后即可分配。
            if (!needsCredential) {
                throw BizIllegalException.invalid("请输入访问凭据");
            }
            id = IdWorker.getId();
            models.insertModel(id, revisionId);
        } else {
            // 编辑读取真实当前版本，避免覆盖并行办理。
            id = modelId;
            current = checkedModel(id, input.getVersion());
            if (!capability(input.getProtocol()).equals(capability(current.protocol()))
                    && models.assignedCount(id) > 0) {
                // 变更模型能力前必须解除旧分配，避免已有节点接入错误协议。
                throw BizIllegalException.state("请先解除节点分配，再修改模型类型");
            }
            if (needsCredential
                    || !endpoint.equals(current.endpoint())
                    || !input.getProtocol().equals(current.protocol())) {
                // 改变认证资料的目标或协议同样需要凭据维护权。
                permissions.require("models.credential");
            }
        }
        long credentialId;
        if (needsCredential) {
            // 只有明确替换才保存新的凭据修订。
            credentialId = IdWorker.getId();
            String keyVersion = credentials.version();
            models.insertCredential(
                    credentialId,
                    id,
                    keyVersion,
                    credentials.encrypt(input.getCredential().strip(), context(id, credentialId), keyVersion));
        } else {
            credentialId = Objects.requireNonNull(current).credentialId();
        }
        var next = new Config(
                id,
                revisionId,
                current == null ? 1 : current.revision() + 1,
                input.getName().strip(),
                input.getProtocol(),
                endpoint,
                input.getModelName().strip(),
                input.getTimeoutSeconds(),
                current == null || current.enabled(),
                current == null ? 1 : current.version() + 1,
                credentialId);
        models.insertRevision(next);
        if (current != null) {
            // 原任务仍引用原修订，新任务读取新修订。
            models.publish(id, revisionId);
        } else {
            // 初次启用与首次配置一同成为可回看的状态。
            models.recordUse(id, 1, true);
        }
        return view(next);
    }

    /**
     * 停用或恢复模型，保留节点关系与配置历史。
     */
    @Transactional
    public void setState(long id, ModelStateDTO input) {
        var current = checkedModel(id, input.getVersion());
        if (input.getEnabled() != current.enabled()) {
            // 用户的当前版本与服务端匹配才改变状态。
            if (models.setEnabled(id, current.version(), input.getEnabled()) != 1) {
                // 避免并行维护写出同一模型版本的两条状态。
                throw BizIllegalException.conflict();
            }
            models.recordUse(id, current.version() + 1, input.getEnabled());
        }
    }

    /**
     * 保存独立的业务节点选择。
     */
    @Transactional
    public void assign(String node, ModelAssignmentDTO input) {
        Config selected = null;
        if (input.getModelId() != null) {
            // 与停用按同一模型行串行检查。
            selected = models.lockModel(input.getModelId());
            requireEnabled(selected);
        }
        var current = models.lockAssignment(node);
        if (current == null) {
            throw BizIllegalException.missing();
        }
        if (current.version() != input.getVersion()) {
            throw BizIllegalException.conflict();
        }
        if (selected != null && !CONNECTED_NODES.contains(node)) {
            // 只有实际读取独立分配的节点接受配置，仍允许按既有流程清空历史分配。
            throw BizIllegalException.state("当前节点没有独立模型调用，暂不能分配模型");
        }
        if (selected != null && !current.capability().equals(capability(selected.protocol()))) {
            // 直接调用接口也不能把能力不匹配的模型分配给节点。
            throw BizIllegalException.invalid("模型类型不符合该节点所需能力");
        }
        if (!Objects.equals(current.modelId(), input.getModelId())) {
            models.assign(node, input.getModelId());
        }
    }

    /**
     * 返回注册接口格式所具备的实际模型能力。
     */
    private String capability(String protocol) {
        return switch (protocol) {
            case "OPENAI_EMBEDDINGS" -> "EMBEDDING";
            case "COHERE_RERANK", "JSON_RERANK" -> "RERANK";
            default -> "GENERATION";
        };
    }

    /**
     * 解析任务应固定的节点配置修订。
     */
    public long requireBinding(String node) {
        Long revision = activeBinding(node);
        if (revision == null) {
            throw BizIllegalException.state("当前节点未分配可用模型");
        }
        return revision;
    }

    /**
     * 对仅在需要时调用的节点，也在主任务开始时固定可用修订。
     */
    public Long activeBinding(String node) {
        return models.binding(node);
    }

    /**
     * 在最终切换事务内保持确切节点及模型版本，不让停用、改绑或编辑穿过切换窗口。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCurrentBinding(String node, long revision) {
        var pinned = models.revision(revision);
        var current = pinned == null ? null : models.lockModel(pinned.id());
        // 沿用模型修改的模型行在先、分配行在后的锁顺序。
        var assignment = models.lockAssignment(node);
        if (current == null
                || !current.enabled()
                || current.revisionId() != revision
                || assignment == null
                || !Objects.equals(assignment.modelId(), current.id())) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "向量模型配置已变化，请重新核对发布候选");
        }
        checkedEndpoint(current.endpoint());
    }

    /**
     * 只使用任务已固定的修订，重新核对当前启停和目的地。
     */
    public ModelInvocation invocation(Long revisionId, int remainingSeconds) {
        var config = revisionId == null ? null : models.revision(revisionId);
        return invocation(config, remainingSeconds);
    }

    /**
     * 保留回执指纹所绑定的不可变模型字段，供问数内部回读使用。
     *
     * @param revisionId 原任务冻结的配置修订编号
     * @param protocol 原请求的接口格式
     * @param endpoint 原请求的服务地址
     * @param modelName 原请求的模型名称
     */
    public record RevisionReference(String revisionId, String protocol, String endpoint, String modelName) {}

    /**
     * 回执查验沿用原修订标识，不读取凭据也不重新批准外发。
     */
    public RevisionReference revisionReference(long revisionId) {
        var config = models.revision(revisionId);
        if (config == null) {
            // 无法重建原请求身份时，不能使用其他修订的回执。
            throw BizIllegalException.missing();
        }
        return new RevisionReference(
                Long.toString(config.revisionId()),
                config.protocol(),
                config.endpoint().strip(),
                config.modelName());
    }

    /**
     * 多轮调用前核对原修订当前是否仍可外发，无需再次解密。
     */
    public void verifyActive(long revisionId) {
        var config = models.revision(revisionId);
        requireEnabled(config);
        checkedEndpoint(config.endpoint());
    }

    /**
     * 测试已保存的当前修订，避免详情打开后测试到另一组凭据。
     */
    public ModelInvocation connectionTestInvocation(long modelId, long revisionId) {
        var config = models.current(modelId);
        if (config == null) {
            // 无法定位的模型不产生测试。
            throw BizIllegalException.missing();
        }
        if (config.revisionId() != revisionId) {
            // 并行编辑后由操作者重新查看并选择实际修订。
            throw BizIllegalException.conflict();
        }
        return invocation(config, 15);
    }

    /**
     * 在调用边界复核当前启停、目标主机和秘密归属。
     */
    private ModelInvocation invocation(Config config, int remainingSeconds) {
        requireEnabled(config);
        String endpoint = checkedEndpoint(config.endpoint());
        var secret = models.credential(config.credentialId(), config.id());
        String key = credentials.decrypt(
                secret.ciphertext(), context(config.id(), config.credentialId()), secret.keyVersion());
        return new ModelInvocation(
                Long.toString(config.revisionId()),
                config.protocol(),
                endpoint,
                config.modelName(),
                Math.min(config.timeoutSeconds(), remainingSeconds),
                key);
    }

    /**
     * 校验当前并发版本，资源缺失时返回统一错误。
     */
    private Config checkedModel(long id, Integer version) {
        var config = models.lockModel(id);
        if (config == null) {
            throw BizIllegalException.missing();
        }
        if (!Objects.equals(config.version(), version)) {
            throw BizIllegalException.conflict();
        }
        return config;
    }

    /**
     * 阻断未配置或当前停用的模型外发。
     */
    private static void requireEnabled(Config config) {
        if (config == null || !config.enabled()) {
            throw BizIllegalException.state("当前模型已停用或未配置");
        }
    }

    /**
     * 模型基址及完整接口地址均须使用部署准入的 HTTPS 主机。
     */
    private String checkedEndpoint(String address) {
        URI uri;
        try {
            uri = URI.create(address.strip());
        } catch (IllegalArgumentException ignored) {
            // 语法错误直接关联输入，不回显提交地址。
            throw endpointError("请输入有效的 HTTPS 服务地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            // URL 只定位服务，访问凭据通过独立认证头发送。
            throw endpointError("填写 HTTPS 服务地址，使用默认端口，去掉查询参数和片段");
        }
        if (Arrays.stream(allowedHosts.split(",")).map(String::strip).noneMatch(uri.getHost()::equalsIgnoreCase)) {
            // 合法 URL 但主机不在部署准入清单时，提示实际处理方式。
            throw endpointError("此服务主机未获准访问，请使用已准入的主机或联系部署管理员");
        }
        return uri.toString();
    }

    /**
     * 保留服务地址字段定位信息供模型表单显示。
     */
    private static FieldValidationException endpointError(String message) {
        return new FieldValidationException(List.of(new FieldErrorVO("endpoint", "VALIDATION_FAILED", message)));
    }

    /**
     * 加密认证绑定模型和凭据修订，防止跨资源替换密文。
     */
    private static String context(long id, long credentialId) {
        return "model:credential:" + id + ":" + credentialId;
    }

    /**
     * 只映射后台可披露字段。
     */
    private static ModelConfigVO view(Config row) {
        return new ModelConfigVO(
                Long.toString(row.id()),
                Long.toString(row.revisionId()),
                row.revision(),
                row.name(),
                row.protocol(),
                row.endpoint(),
                row.modelName(),
                row.timeoutSeconds(),
                row.enabled(),
                row.version());
    }
}
