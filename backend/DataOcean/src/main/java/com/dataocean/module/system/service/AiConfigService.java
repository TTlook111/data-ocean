package com.dataocean.module.system.service;

import com.dataocean.module.system.entity.dto.AiConfigDTO;
import com.dataocean.module.system.entity.vo.AiConfigVO;

import java.util.List;
import java.util.Map;

/**
 * AI 配置管理服务接口。
 * <p>
 * 封装 AiConfigController 中的业务逻辑，包括配置读写、Provider CRUD、
 * 默认值迁移、密钥处理等。
 * </p>
 */
public interface AiConfigService {

    /**
     * 获取完整 AI 配置。
     *
     * @return AI 配置视图对象
     */
    AiConfigVO getConfig();

    /**
     * 更新 AI 配置。
     *
     * @param dto 配置更新请求
     * @return 更新后的 AI 配置
     */
    AiConfigVO updateConfig(AiConfigDTO dto);

    /**
     * 获取所有供应商列表。
     *
     * @param includeSecret 是否包含密钥信息
     * @return 供应商列表
     */
    List<AiConfigVO.Provider> getProviders(boolean includeSecret);

    /**
     * 创建或更新供应商。
     *
     * @param payload 供应商信息
     * @return 供应商信息（已脱敏）
     */
    AiConfigVO.Provider upsertProvider(AiConfigDTO.ProviderPayload payload);

    /**
     * 删除供应商。
     *
     * @param id 供应商 ID
     */
    void deleteProvider(String id);

    /**
     * 获取单个供应商。
     *
     * @param id           供应商 ID
     * @param includeSecret 是否包含密钥信息
     * @return 供应商信息
     */
    AiConfigVO.Provider getProvider(String id, boolean includeSecret);

    /**
     * 更新供应商状态。
     *
     * @param provider 供应商信息
     */
    void saveProvider(AiConfigVO.Provider provider);

    /**
     * 解密供应商 API Key。
     *
     * @param provider 供应商信息
     * @return 解密后的 API Key
     */
    String decryptApiKey(AiConfigVO.Provider provider);

    /**
     * 脱敏供应商信息。
     *
     * @param provider 供应商信息
     * @return 脱敏后的供应商信息
     */
    AiConfigVO.Provider maskProvider(AiConfigVO.Provider provider);

    /**
     * 合并模型列表。
     *
     * @param provider 供应商信息
     * @param response Python 服务响应
     */
    void mergeModelLists(AiConfigVO.Provider provider, Map<String, Object> response);
}
