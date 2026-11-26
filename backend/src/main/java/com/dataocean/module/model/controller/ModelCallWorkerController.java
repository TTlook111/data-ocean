package com.dataocean.module.model.controller;

import com.dataocean.common.result.Result;
import com.dataocean.module.iam.security.RequirePermission;
import com.dataocean.module.model.entity.dto.ModelCallFinishDTO;
import com.dataocean.module.model.entity.dto.ModelCallStartDTO;
import com.dataocean.module.model.service.ModelCallReceiptService;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 仅供已签名执行服务写入无业务正文的模型诊断。
 */
@RestController
@RequestMapping("/internal/v1/model-calls")
@ConditionalOnProperty(name = "dataocean.governance-ai.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ModelCallWorkerController {

    /**
     * 按固定请求标识保留开始及结果。
     */
    private final ModelCallReceiptService receipts;

    /**
     * 确认外发前已登记本次诊断。
     */
    @PutMapping("/{id}/start")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public Result<Boolean> start(@PathVariable UUID id, @Valid @RequestBody ModelCallStartDTO input) {
        receipts.start(id, input);
        return Result.success(true);
    }

    /**
     * 幂等记录提供方响应及已知用量。
     */
    @PutMapping("/{id}/finish")
    @RequirePermission(access = RequirePermission.Access.SERVICE)
    public Result<Boolean> finish(@PathVariable UUID id, @Valid @RequestBody ModelCallFinishDTO input) {
        receipts.finish(id, input);
        return Result.success(true);
    }
}
