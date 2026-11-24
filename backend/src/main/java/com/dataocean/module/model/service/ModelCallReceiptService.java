package com.dataocean.module.model.service;

import com.dataocean.module.model.entity.dto.ModelCallFinishDTO;
import com.dataocean.module.model.entity.dto.ModelCallStartDTO;

import java.util.UUID;

/**
 * 接收受认证模型执行服务的诊断回执。
 */
public interface ModelCallReceiptService {

    /**
     * 在请求提交提供方前落库，重传沿用首次身份。
     */
    void start(UUID id, ModelCallStartDTO input);

    /**
     * 幂等接收同一次请求的实际结果。
     */
    void finish(UUID id, ModelCallFinishDTO input);
}
