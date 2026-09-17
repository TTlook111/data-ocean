package com.dataocean.module.permission.s1.bootstrap;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.permission.s1.service.IamS1BootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 启动式 bootstrap 入口；只接受明确账号 ID，不提供 HTTP 后门。
 */
@Component
@RequiredArgsConstructor
public class IamS1BootstrapRunner implements ApplicationRunner {

    private final Environment environment;
    private final IamS1BootstrapService bootstrapService;

    @Override
    public void run(ApplicationArguments args) {
        if (!IamS1BootstrapMode.isEnabled(environment)) {
            return;
        }
        String targetUserId = IamS1BootstrapMode.targetUserId(environment);
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new BusinessException("IAM-SIMPLE-1 bootstrap 缺少明确目标账号 ID");
        }
        try {
            bootstrapService.bootstrap(Long.parseLong(targetUserId), UUID.randomUUID().toString());
        } catch (NumberFormatException exception) {
            throw new BusinessException("IAM-SIMPLE-1 bootstrap 目标账号 ID 不合法");
        }
    }
}
