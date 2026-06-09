package com.dataocean.module.system.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.datasource.service.DatasourceSecretService;
import com.dataocean.module.system.client.PythonAiConfigClient;
import com.dataocean.module.system.entity.dto.AiConfigDTO;
import com.dataocean.module.system.entity.vo.AiConfigVO;
import com.dataocean.module.system.service.AiConfigService;
import com.dataocean.module.system.service.SysConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 配置管理服务实现类。
 * <p>
 * 封装 AiConfigController 中的业务逻辑，包括配置读写、Provider CRUD、
 * 默认值迁移、密钥处理等。
 * </p>
 */
@Service
@Slf4j
public class AiConfigServiceImpl implements AiConfigService {

    private static final String PROVIDER_PREFIX = "ai.provider.";
    private static final String ACTIVE_CHAT_KEY = "ai.active.chat";
    private static final String ACTIVE_EMBEDDING_KEY = "ai.active.embedding";
    private static final String PENDING_EMBEDDING_KEY = "ai.pending.embedding";
    private static final String VECTORIZE_STATUS_KEY = "ai.vectorize.status";
    private static final String DEFAULT_PROVIDER_ID = "dashscope";
    private static final String DEFAULT_COLLECTION = "schema_knowledge";

    private final SysConfigService configService;
    private final DatasourceSecretService secretService;
    private final ObjectMapper objectMapper;
    private final PythonAiConfigClient pythonAiConfigClient;

    public AiConfigServiceImpl(SysConfigService configService,
                               DatasourceSecretService secretService,
                               ObjectMapper objectMapper,
                               PythonAiConfigClient pythonAiConfigClient) {
        this.configService = configService;
        this.secretService = secretService;
        this.objectMapper = objectMapper;
        this.pythonAiConfigClient = pythonAiConfigClient;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AiConfigVO getConfig() {
        ensureLegacyDefaults();
        AiConfigVO vo = new AiConfigVO();
        List<AiConfigVO.Provider> providers = getProviders(false);
        AiConfigVO.ChatConfig chat = readJson(ACTIVE_CHAT_KEY, AiConfigVO.ChatConfig.class, defaultChat());
        AiConfigVO.EmbeddingConfig embedding = readJson(ACTIVE_EMBEDDING_KEY, AiConfigVO.EmbeddingConfig.class, defaultEmbedding());
        AiConfigVO.EmbeddingConfig pending = readJson(PENDING_EMBEDDING_KEY, AiConfigVO.EmbeddingConfig.class, null);
        AiConfigVO.VectorizeStatus status = readJson(VECTORIZE_STATUS_KEY, AiConfigVO.VectorizeStatus.class, defaultVectorizeStatus(embedding));

        vo.setProviders(providers);
        vo.setActiveChat(chat);
        vo.setActiveEmbedding(embedding);
        vo.setPendingEmbedding(pending);
        vo.setVectorizeStatus(status);

        AiConfigVO.Provider provider = providers.stream()
                .filter(p -> p.getId().equals(chat.getProviderId()))
                .findFirst()
                .orElse(providers.isEmpty() ? null : providers.get(0));
        vo.setApiKeyMasked(provider == null ? "" : provider.getApiKeyMasked());
        vo.setBaseUrl(provider == null ? "" : provider.getBaseUrl());
        vo.setModel(chat.getModel());
        vo.setTemperature(chat.getTemperature());
        vo.setTimeout(chat.getTimeout());
        vo.setEmbeddingModel(embedding.getModel());
        vo.setEmbeddingDimension(String.valueOf(embedding.getDimension()));
        return vo;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AiConfigVO updateConfig(AiConfigDTO dto) {
        ensureLegacyDefaults();
        AiConfigVO.ChatConfig chat = dto.getChat() == null ? null : toChat(dto.getChat());
        AiConfigVO.EmbeddingConfig requestedEmbedding = dto.getEmbedding() == null ? null : toEmbedding(dto.getEmbedding());
        AiConfigVO.EmbeddingConfig activeEmbedding = readJson(ACTIVE_EMBEDDING_KEY, AiConfigVO.EmbeddingConfig.class, defaultEmbedding());

        if (chat == null) {
            chat = readJson(ACTIVE_CHAT_KEY, AiConfigVO.ChatConfig.class, defaultChat());
            if (dto.getModel() != null) chat.setModel(dto.getModel());
            if (dto.getTemperature() != null) chat.setTemperature(dto.getTemperature());
            if (dto.getTimeout() != null) chat.setTimeout(dto.getTimeout());
        }

        if (requestedEmbedding == null) {
            requestedEmbedding = activeEmbedding;
            if (dto.getEmbeddingModel() != null) requestedEmbedding.setModel(dto.getEmbeddingModel());
            if (dto.getEmbeddingDimension() != null) {
                requestedEmbedding.setDimension(parseInt(dto.getEmbeddingDimension(), requestedEmbedding.getDimension()));
            }
        }

        if (dto.getBaseUrl() != null || dto.getApiKey() != null) {
            AiConfigDTO.ProviderPayload provider = new AiConfigDTO.ProviderPayload();
            provider.setId(chat.getProviderId() == null ? DEFAULT_PROVIDER_ID : chat.getProviderId());
            provider.setBaseUrl(dto.getBaseUrl());
            provider.setApiKey(dto.getApiKey());
            upsertProvider(provider);
        }

        configService.setValue(ACTIVE_CHAT_KEY, toJson(chat));
        if (embeddingChanged(activeEmbedding, requestedEmbedding)) {
            configService.setValue(PENDING_EMBEDDING_KEY, toJson(requestedEmbedding));
            AiConfigVO.VectorizeStatus status = defaultVectorizeStatus(activeEmbedding);
            status.setStatus("REINDEX_REQUIRED");
            status.setPending(requestedEmbedding);
            status.setStartedAt(LocalDateTime.now().toString());
            configService.setValue(VECTORIZE_STATUS_KEY, toJson(status));
        } else {
            configService.setValue(ACTIVE_EMBEDDING_KEY, toJson(requestedEmbedding));
            configService.deleteValue(PENDING_EMBEDDING_KEY);
            configService.setValue(VECTORIZE_STATUS_KEY, toJson(defaultVectorizeStatus(requestedEmbedding)));
        }
        writeLegacyKeys(chat, activeEmbedding);
        pythonAiConfigClient.notifyReloadBestEffort();
        return getConfig();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AiConfigVO.Provider> getProviders(boolean includeSecret) {
        ensureLegacyDefaults();
        Map<String, String> rows = configService.getByPrefix(PROVIDER_PREFIX);
        List<AiConfigVO.Provider> providers = new ArrayList<>();
        for (String value : rows.values()) {
            AiConfigVO.Provider provider = fromJson(value, AiConfigVO.Provider.class);
            if (provider != null) {
                providers.add(includeSecret ? provider : maskProvider(provider));
            }
        }
        return providers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AiConfigVO.Provider upsertProvider(AiConfigDTO.ProviderPayload payload) {
        ensureProviderId(payload);
        AiConfigVO.Provider existing = readJson(PROVIDER_PREFIX + payload.getId(), AiConfigVO.Provider.class, null);
        AiConfigVO.Provider provider = existing == null ? new AiConfigVO.Provider() : existing;
        provider.setId(payload.getId());
        if (payload.getName() != null) provider.setName(payload.getName());
        if (payload.getBaseUrl() != null) provider.setBaseUrl(payload.getBaseUrl());
        if (payload.getApiKey() != null && !payload.getApiKey().isBlank()) {
            provider.setApiKeyMasked(secretService.encrypt(payload.getApiKey()));
        }
        if (payload.getChatModels() != null) provider.setChatModels(toModelItems(payload.getChatModels()));
        if (payload.getEmbeddingModels() != null) provider.setEmbeddingModels(toModelItems(payload.getEmbeddingModels()));
        if (provider.getName() == null || provider.getName().isBlank()) provider.setName(provider.getId());
        if (provider.getStatus() == null) provider.setStatus("unknown");
        saveProvider(provider);
        return provider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deleteProvider(String id) {
        AiConfigVO.ChatConfig chat = readJson(ACTIVE_CHAT_KEY, AiConfigVO.ChatConfig.class, defaultChat());
        AiConfigVO.EmbeddingConfig embedding = readJson(ACTIVE_EMBEDDING_KEY, AiConfigVO.EmbeddingConfig.class, defaultEmbedding());
        AiConfigVO.EmbeddingConfig pending = readJson(PENDING_EMBEDDING_KEY, AiConfigVO.EmbeddingConfig.class, null);
        if (id.equals(chat.getProviderId())) {
            throw new BusinessException("当前 Chat 模型正在使用此供应商");
        }
        if (id.equals(embedding.getProviderId())) {
            throw new BusinessException("当前 Embedding 模型正在使用此供应商");
        }
        if (pending != null && id.equals(pending.getProviderId())) {
            throw new BusinessException("当前 pending Embedding 正在使用此供应商");
        }
        configService.deleteValue(PROVIDER_PREFIX + id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AiConfigVO.Provider getProvider(String id, boolean includeSecret) {
        AiConfigVO.Provider provider = readJson(PROVIDER_PREFIX + id, AiConfigVO.Provider.class, null);
        if (provider == null) {
            throw new BusinessException("供应商不存在：" + id);
        }
        return includeSecret ? provider : maskProvider(provider);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void saveProvider(AiConfigVO.Provider provider) {
        configService.setValue(PROVIDER_PREFIX + provider.getId(), toJson(provider));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String decryptApiKey(AiConfigVO.Provider provider) {
        if (provider.getApiKeyMasked() == null || provider.getApiKeyMasked().isBlank()) return "";
        try {
            return secretService.decrypt(provider.getApiKeyMasked());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AiConfigVO.Provider maskProvider(AiConfigVO.Provider provider) {
        AiConfigVO.Provider masked = new AiConfigVO.Provider();
        BeanUtils.copyProperties(provider, masked);
        String encrypted = provider.getApiKeyMasked();
        if (encrypted == null || encrypted.isBlank()) {
            masked.setApiKeyMasked("");
            return masked;
        }
        try {
            masked.setApiKeyMasked(maskApiKey(secretService.decrypt(encrypted)));
        } catch (Exception e) {
            masked.setApiKeyMasked("****");
        }
        return masked;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public void mergeModelLists(AiConfigVO.Provider provider, Map<String, Object> response) {
        if (response == null) return;
        Object data = response.getOrDefault("data", response);
        if (data instanceof Map<?, ?> map) {
            Object chatModels = map.get("chatModels");
            Object embeddingModels = map.get("embeddingModels");
            if (chatModels instanceof List<?> chatList) {
                provider.setChatModels(objectMapper.convertValue(chatList, new TypeReference<List<AiConfigVO.ModelItem>>() {}));
            }
            if (embeddingModels instanceof List<?> embeddingList) {
                provider.setEmbeddingModels(objectMapper.convertValue(embeddingList, new TypeReference<List<AiConfigVO.ModelItem>>() {}));
            }
        }
    }

    // ========== 私有辅助方法 ==========

    private void ensureLegacyDefaults() {
        if (configService.getValue(PROVIDER_PREFIX + DEFAULT_PROVIDER_ID) == null) {
            AiConfigVO.Provider provider = new AiConfigVO.Provider();
            provider.setId(DEFAULT_PROVIDER_ID);
            provider.setName("通义千问");
            provider.setBaseUrl(configService.getValue("ai.dashscope.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
            String encryptedKey = configService.getValue("ai.dashscope.apiKey", "");
            provider.setApiKeyMasked(encryptedKey);
            provider.setStatus(encryptedKey == null || encryptedKey.isBlank() ? "unknown" : "configured");
            provider.setChatModels(List.of(model(configService.getValue("ai.llm.model", "qwen-plus"), "chat", null)));
            provider.setEmbeddingModels(List.of(model(
                    configService.getValue("ai.embedding.model", "text-embedding-v4"),
                    "embedding",
                    parseInt(configService.getValue("ai.embedding.dimension", "1024"), 1024))));
            saveProvider(provider);
        }
        if (configService.getValue(ACTIVE_CHAT_KEY) == null) {
            configService.setValue(ACTIVE_CHAT_KEY, toJson(defaultChat()));
        }
        if (configService.getValue(ACTIVE_EMBEDDING_KEY) == null) {
            configService.setValue(ACTIVE_EMBEDDING_KEY, toJson(defaultEmbedding()));
        }
        if (configService.getValue(VECTORIZE_STATUS_KEY) == null) {
            configService.setValue(VECTORIZE_STATUS_KEY, toJson(defaultVectorizeStatus(defaultEmbedding())));
        }
    }

    private void writeLegacyKeys(AiConfigVO.ChatConfig chat, AiConfigVO.EmbeddingConfig embedding) {
        AiConfigVO.Provider provider = getProvider(chat.getProviderId(), true);
        configService.setValue("ai.dashscope.baseUrl", provider.getBaseUrl());
        configService.setValue("ai.llm.model", chat.getModel());
        configService.setValue("ai.llm.temperature", chat.getTemperature());
        configService.setValue("ai.llm.timeout", chat.getTimeout());
        configService.setValue("ai.embedding.model", embedding.getModel());
        configService.setValue("ai.embedding.dimension", String.valueOf(embedding.getDimension()));
    }

    private boolean embeddingChanged(AiConfigVO.EmbeddingConfig active, AiConfigVO.EmbeddingConfig requested) {
        if (active == null || requested == null) return false;
        return !same(active.getProviderId(), requested.getProviderId())
                || !same(active.getModel(), requested.getModel())
                || !same(String.valueOf(active.getDimension()), String.valueOf(requested.getDimension()))
                || !same(active.getCollection(), requested.getCollection());
    }

    private boolean same(String left, String right) {
        String l = left == null ? "" : left;
        String r = right == null ? "" : right;
        return l.equals(r);
    }

    private AiConfigVO.ChatConfig defaultChat() {
        AiConfigVO.ChatConfig chat = new AiConfigVO.ChatConfig();
        chat.setProviderId(DEFAULT_PROVIDER_ID);
        chat.setModel(configService.getValue("ai.llm.model", "qwen-plus"));
        chat.setTemperature(configService.getValue("ai.llm.temperature", "0.3"));
        chat.setTimeout(configService.getValue("ai.llm.timeout", "120"));
        chat.setMaxRetries(configService.getValue("ai.llm.maxRetries", "2"));
        return chat;
    }

    private AiConfigVO.EmbeddingConfig defaultEmbedding() {
        AiConfigVO.EmbeddingConfig embedding = new AiConfigVO.EmbeddingConfig();
        embedding.setProviderId(DEFAULT_PROVIDER_ID);
        embedding.setModel(configService.getValue("ai.embedding.model", "text-embedding-v4"));
        embedding.setDimension(parseInt(configService.getValue("ai.embedding.dimension", "1024"), 1024));
        embedding.setCollection(configService.getValue("ai.embedding.collection", DEFAULT_COLLECTION));
        embedding.setIndexVersion(configService.getValue("ai.embedding.indexVersion", "v1"));
        return embedding;
    }

    private AiConfigVO.VectorizeStatus defaultVectorizeStatus(AiConfigVO.EmbeddingConfig embedding) {
        AiConfigVO.VectorizeStatus status = new AiConfigVO.VectorizeStatus();
        status.setStatus("NORMAL");
        status.setActive(embedding);
        status.setTotalChunks(0);
        status.setCompletedChunks(0);
        status.setFailedChunks(0);
        return status;
    }

    private void ensureProviderId(AiConfigDTO.ProviderPayload payload) {
        if (payload == null || payload.getId() == null || payload.getId().isBlank()) {
            throw new BusinessException("供应商 ID 不能为空");
        }
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private <T> T readJson(String key, Class<T> type, T defaultValue) {
        String value = configService.getValue(key);
        T parsed = fromJson(value, type);
        return parsed == null ? defaultValue : parsed;
    }

    private <T> T fromJson(String value, Class<T> type) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception e) {
            log.warn("AI 配置 JSON 解析失败 type={} reason={}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("AI 配置序列化失败");
        }
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private AiConfigVO.ChatConfig toChat(AiConfigDTO.ChatConfig source) {
        AiConfigVO.ChatConfig target = new AiConfigVO.ChatConfig();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private AiConfigVO.EmbeddingConfig toEmbedding(AiConfigDTO.EmbeddingConfig source) {
        AiConfigVO.EmbeddingConfig target = new AiConfigVO.EmbeddingConfig();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    private List<AiConfigVO.ModelItem> toModelItems(List<AiConfigDTO.ModelItem> sources) {
        List<AiConfigVO.ModelItem> items = new ArrayList<>();
        for (AiConfigDTO.ModelItem source : sources) {
            AiConfigVO.ModelItem item = new AiConfigVO.ModelItem();
            BeanUtils.copyProperties(source, item);
            items.add(item);
        }
        return items;
    }

    private AiConfigVO.ModelItem model(String name, String type, Integer dimension) {
        AiConfigVO.ModelItem item = new AiConfigVO.ModelItem();
        item.setName(name);
        item.setDisplayName(name);
        item.setType(type);
        item.setDimension(dimension);
        return item;
    }
}
