package com.dataocean.module.model.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.ErrorVO.FieldErrorVO;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.FieldValidationException;
import com.dataocean.infrastructure.CredentialProtection;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.entity.dto.ModelAssignmentDTO;
import com.dataocean.module.model.entity.dto.ModelSaveDTO;
import com.dataocean.module.model.entity.dto.ModelStateDTO;
import com.dataocean.module.model.entity.vo.ModelCallVO;
import com.dataocean.module.model.entity.vo.ModelConfigVO;
import com.dataocean.module.model.entity.vo.ModelNodeVO;
import com.dataocean.module.model.mapper.ModelMapper;
import com.dataocean.module.model.mapper.ModelMapper.Config;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
     * 读取带当次配置的调用记录。
     */
    public PageVO<ModelCallVO> calls(PageQuery page) {
        return PageVO.from(models.calls(page.toFixedOrderMpPage()), call -> call);
    }

    /**
     * 列出已接入的节点及当前选择。
     */
    public List<ModelNodeVO> nodes() {
        return models.assignments().stream().map(row -> new ModelNodeVO(
                row.nodeCode(), Objects.toString(row.modelId(), null), row.version())).toList();
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
        boolean needsCredential = input.getCredential() != null && !input.getCredential().isBlank();
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
            if (needsCredential || !endpoint.equals(current.endpoint()) || !input.getProtocol().equals(current.protocol())) {
                // 改变认证资料的目标或协议同样需要凭据维护权。
                permissions.require("models.credential");
            }
        }
        long credentialId;
        if (needsCredential) {
            // 只有明确替换才保存新的凭据修订。
            credentialId = IdWorker.getId();
            String keyVersion = credentials.version();
            models.insertCredential(credentialId, id, keyVersion,
                    credentials.encrypt(input.getCredential().strip(), context(id, credentialId), keyVersion));
        } else {
            credentialId = Objects.requireNonNull(current).credentialId();
        }
        var next = new Config(id, revisionId, current == null ? 1 : current.revision() + 1,
                input.getName().strip(), input.getProtocol(), endpoint, input.getModelName().strip(), input.getTimeoutSeconds(),
                current == null || current.enabled(), current == null ? 1 : current.version() + 1, credentialId);
        models.insertRevision(next);
        if (current != null) {
            // 原任务仍引用原修订，新任务读取新修订。
            models.publish(id, revisionId);
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
            models.setEnabled(id, input.getEnabled());
        }
    }

    /**
     * 保存独立的业务节点选择。
     */
    @Transactional
    public void assign(String node, ModelAssignmentDTO input) {
        if (input.getModelId() != null) {
            // 与停用按同一模型行串行检查。
            requireEnabled(models.lockModel(input.getModelId()));
        }
        var current = models.lockAssignment(node);
        if (current == null) {
            throw BizIllegalException.missing();
        }
        if (current.version() != input.getVersion()) {
            throw BizIllegalException.conflict();
        }
        if (!Objects.equals(current.modelId(), input.getModelId())) {
            models.assign(node, input.getModelId());
        }
    }

    /**
     * 解析任务应固定的节点配置修订。
     */
    public long requireBinding(String node) {
        Long revision = models.binding(node);
        if (revision == null) {
            throw BizIllegalException.state("当前节点未分配可用模型");
        }
        return revision;
    }

    /**
     * 只使用任务已固定的修订，重新核对当前启停和目的地。
     */
    public ModelInvocation invocation(Long revisionId, int remainingSeconds) {
        var config = revisionId == null ? null : models.revision(revisionId);
        return invocation(config, remainingSeconds);
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
        String key = credentials.decrypt(secret.ciphertext(), context(config.id(), config.credentialId()),
                secret.keyVersion());
        return new ModelInvocation(Long.toString(config.revisionId()), config.protocol(), endpoint, config.modelName(),
                Math.min(config.timeoutSeconds(), remainingSeconds), key);
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
     * 服务地址使用部署准入的 HTTPS 基址，避免目标和秘密混用。
     */
    private String checkedEndpoint(String address) {
        URI uri;
        try {
            uri = URI.create(address.strip());
        } catch (IllegalArgumentException ignored) {
            // 语法错误直接关联输入，不回显提交地址。
            throw endpointError("请输入有效的 HTTPS 服务地址");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) {
            // 只接受 HTTPS SDK 基址，连接凭据不得嵌进 URL。
            throw endpointError("填写 HTTPS 服务基址，使用默认端口，去掉查询参数和片段");
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
        return new ModelConfigVO(Long.toString(row.id()), Long.toString(row.revisionId()), row.revision(), row.name(),
                row.protocol(), row.endpoint(), row.modelName(), row.timeoutSeconds(), row.enabled(), row.version());
    }
}
