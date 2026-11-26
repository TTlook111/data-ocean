package com.dataocean.module.model.service.impl;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.model.entity.dto.ModelCallFinishDTO;
import com.dataocean.module.model.entity.dto.ModelCallStartDTO;
import com.dataocean.module.model.mapper.ModelCallReceiptMapper;
import com.dataocean.module.model.mapper.ModelCallReceiptMapper.Identity;
import com.dataocean.module.model.service.ModelCallReceiptService;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 管理一次提供方调用对应的持久化记录。
 */
@Service
@RequiredArgsConstructor
public class ModelCallReceiptServiceImpl implements ModelCallReceiptService {

    /**
     * 模型请求诊断落库入口。
     */
    private final ModelCallReceiptMapper mapper;

    /**
     * 以数据库约束保证并发重传使用相同的调用身份。
     */
    @Override
    @Transactional
    public void start(UUID id, ModelCallStartDTO input) {
        long revision = Ids.parse(input.getRevisionId());
        String key = id.toString();
        mapper.start(key, input.getModule(), input.getOriginId(), input.getNodeCode(), revision);
        if (!new Identity(input.getModule(), input.getOriginId(), input.getNodeCode(), revision)
                .equals(mapper.identity(key))) {
            // 未知节点、修订不匹配或同标识对应不同任务，均不能确认登记。
            throw BizIllegalException.invalid("模型调用回执身份无效");
        }
    }

    /**
     * 已结束的同一调用可安全重传，禁止在无登记时创建虚假结果。
     */
    @Override
    @Transactional
    public void finish(UUID id, ModelCallFinishDTO input) {
        String key = id.toString();
        if (mapper.finish(key, input) == 0 && mapper.identity(key) == null) {
            // 保留明确的缺失反馈，供实际执行端有界重试。
            throw BizIllegalException.missing();
        }
    }
}
