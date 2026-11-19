package com.dataocean.module.datasource.security;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.datasource.entity.dto.SourceActionPreviewDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateDTO;
import com.dataocean.module.datasource.entity.dto.SourceCreateProposalDTO;
import com.dataocean.module.datasource.entity.dto.SourceLifecycleDTO;
import com.dataocean.module.datasource.entity.dto.StartCollectionDTO;
import com.dataocean.module.iam.entity.dto.CreatePreviewDTO;
import com.dataocean.module.iam.security.RequestPermission;
import com.dataocean.module.iam.service.PermissionService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 在进入数据源业务前按照请求的实际配置、采集动作或选项用途核权。
 */
@Component
@RequiredArgsConstructor
public class DatasourceRequestPermission implements RequestPermission {

    /**
     * 检查动作使用权及其对应页面权。
     */
    private final PermissionService permissions;

    /**
     * 核对复用接口已绑定入参中的实际操作。
     */
    @Override
    public void require(Object[] arguments) {
        Object input = arguments[arguments.length - 1];
        if (input instanceof SourceActionPreviewDTO preview) {
            // 同一预览路径可以承载配置、采集及替换动作。
            permissions.require(previewOperation(preview));
        } else if (input instanceof StartCollectionDTO start) {
            // 首次发起与重试要求不同的业务权限。
            permissions.require(collectionOperation(start.getRetryOfTaskId() != null));
        } else if (input instanceof SourceLifecycleDTO lifecycle) {
            // 预览和提交使用同一生命周期权限。
            permissions.require(lifecycleOperation(lifecycle.getAction()));
        } else if (input instanceof SourceCreateDTO create) {
            // 保存时按完整目标状态决定是否同时需要启用权限。
            requireCreate(create.getProposal());
        } else if (input instanceof CreatePreviewDTO<?> preview
                && preview.getProposal() instanceof SourceCreateProposalDTO proposal) {
            // 新建预览和正式创建按相同条件准入。
            requireCreate(proposal);
        } else if (input instanceof String purpose) {
            // 数据源选项按当前页面用途开放。
            permissions.require(optionPermission(purpose));
        } else {
            // 缺少受支持的动作参数时拒绝进入业务。
            throw BizIllegalException.denied();
        }
    }

    /**
     * 核对数据源创建及其拟启用资格。
     */
    private void requireCreate(SourceCreateProposalDTO proposal) {
        permissions.require("sources.create");
        if ("ENABLED".equals(proposal.getTargetLifecycle())) {
            // 创建并启用需要另具备启用权。
            permissions.require("sources.enable");
        }
    }

    /**
     * 解析同一路径上的配置、采集及替换动作所需的入口权限。
     */
    private static String previewOperation(SourceActionPreviewDTO preview) {
        return switch (preview.getAction()) {
            case "UPDATE_CONFIG", "REPLACE_CREDENTIAL" -> "sources.edit";
            case "ENABLE" -> "sources.enable";
            case "DISABLE_SOURCE", "RESTORE_SOURCE", "RETIRE_SOURCE" -> lifecycleOperation(preview.getAction());
            case "PAUSE_SCHEDULE", "RESUME_SCHEDULE" -> "sources.pause";
            case "START_COLLECTION" -> collectionOperation(preview.getProposal().hasNonNull("retryOfTaskId"));
            default -> throw BizIllegalException.invalid("无效的来源维护动作");
        };
    }

    /**
     * 同一采集动作在预览、提交及重放核对中使用同一编码。
     */
    public static String collectionOperation(boolean retry) {
        return retry ? "collections.retry" : "collections.collect";
    }

    /**
     * 对三个生命周期动作独立核权。
     */
    public static String lifecycleOperation(String action) {
        return switch (action) {
            case "DISABLE_SOURCE" -> "sources.disable";
            case "RESTORE_SOURCE" -> "sources.enable";
            case "RETIRE_SOURCE" -> "sources.retire";
            default -> throw BizIllegalException.invalid("无效的数据源生命周期动作");
        };
    }

    /**
     * 从限定的选项用途解析对应的页面权。
     */
    private static String optionPermission(String purpose) {
        return switch (purpose) {
            case "CATALOG" -> "catalog.view";
            case "COLLECTIONS" -> "collections.view";
            case "BUSINESS_NOTES" -> "business-notes.view";
            case "GOVERNANCE" -> "governance.view";
            default -> throw BizIllegalException.invalid("数据源选项用途不正确");
        };
    }
}
