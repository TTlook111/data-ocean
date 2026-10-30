package com.dataocean.module.system.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.permission.s1.annotation.IamS1Global;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.system.client.PythonAiConfigClient;
import com.dataocean.module.system.entity.dto.AiConfigDTO;
import com.dataocean.module.system.entity.vo.AiConfigVO;
import com.dataocean.module.system.service.AiConfigService;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 服务配置管理控制器。
 * <p>
 * 仅作为 HTTP 边界层，负责路由、权限校验、Result 包装。
 * 业务逻辑委托给 {@link AiConfigService}，Python 服务调用委托给 {@link PythonAiConfigClient}。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/system/ai-config")
@AdminAuditLog
@Slf4j
public class AiConfigController {

    /** 查看 AI 配置：只返回供应商与模型摘要，不返回密钥原值。 */
    private static final String VIEW_FUNCTION = "system:ai-config:view";
    /** 维护 AI 配置：保存、增删改供应商、测试连接、同步模型、检测维度。 */
    private static final String MANAGE_FUNCTION = "system:ai-config:manage";


    private final AiConfigService aiConfigService;
    private final PythonAiConfigClient pythonAiConfigClient;

    public AiConfigController(AiConfigService aiConfigService, PythonAiConfigClient pythonAiConfigClient) {
        this.aiConfigService = aiConfigService;
        this.pythonAiConfigClient = pythonAiConfigClient;
    }

    /**
     * 获取完整 AI 配置
     */
    @GetMapping
    @IamS1Global(VIEW_FUNCTION)
    public Result<AiConfigVO> getConfig() {
        return Result.success(aiConfigService.getConfig());
    }

    /**
     * 更新 AI 配置
     */
    @PutMapping
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AiConfigVO> updateConfig(@RequestBody AiConfigDTO dto) {
        return Result.success(aiConfigService.updateConfig(dto));
    }

    /**
     * 获取所有供应商列表
     */
    @GetMapping("/providers")
    @IamS1Global(VIEW_FUNCTION)
    public Result<List<AiConfigVO.Provider>> listProviders() {
        return Result.success(aiConfigService.getProviders(false));
    }

    /**
     * 创建供应商
     */
    @PostMapping("/providers")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AiConfigVO.Provider> createProvider(@RequestBody AiConfigDTO.ProviderPayload payload) {
        aiConfigService.upsertProvider(payload);
        return Result.success(aiConfigService.getProvider(payload.getId(), false));
    }

    /**
     * 更新供应商
     */
    @PutMapping("/providers/{id}")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AiConfigVO.Provider> updateProvider(@PathVariable String id, @RequestBody AiConfigDTO.ProviderPayload payload) {
        payload.setId(id);
        aiConfigService.upsertProvider(payload);
        return Result.success(aiConfigService.getProvider(id, false));
    }

    /**
     * 删除供应商
     */
    @DeleteMapping("/providers/{id}")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Void> deleteProvider(@PathVariable String id) {
        aiConfigService.deleteProvider(id);
        return Result.success();
    }

    /**
     * 测试供应商连接
     */
    @PostMapping("/providers/{id}/test")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AiConfigVO.Provider> testProvider(@PathVariable String id) {
        AiConfigVO.Provider provider = aiConfigService.getProvider(id, true);
        boolean success = false;
        try {
            Map<String, Object> response = pythonAiConfigClient.testProvider(
                    provider.getBaseUrl(), aiConfigService.decryptApiKey(provider));
            provider.setStatus("connected");
            provider.setLastTestedAt(java.time.LocalDateTime.now().toString());
            aiConfigService.mergeModelLists(provider, response);
            success = true;
        } catch (Exception e) {
            provider.setStatus("failed");
            provider.setLastTestedAt(java.time.LocalDateTime.now().toString());
            log.warn("供应商连接测试失败 id={} reason={}", id, e.getMessage());
            throw e;
        } finally {
            // 无论成功还是失败，都保存供应商状态
            aiConfigService.saveProvider(provider);
        }
        return Result.success(aiConfigService.maskProvider(provider));
    }

    /**
     * 同步模型列表（等同于测试连接）
     */
    @PostMapping("/providers/{id}/sync-models")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<AiConfigVO.Provider> syncModels(@PathVariable String id) {
        return testProvider(id);
    }

    /**
     * 检测嵌入维度
     */
    @PostMapping("/detect-dimension")
    @IamS1Global(MANAGE_FUNCTION)
    public Result<Map<String, Object>> detectDimension(@RequestBody Map<String, Object> payload) {
        return Result.success(pythonAiConfigClient.detectDimension(payload));
    }

}
