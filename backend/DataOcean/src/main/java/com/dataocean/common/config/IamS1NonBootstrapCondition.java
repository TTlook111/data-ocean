package com.dataocean.common.config;

import com.dataocean.module.permission.s1.bootstrap.IamS1BootstrapMode;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 仅在统一 bootstrap 判定为关闭时启用定时任务。 */
public class IamS1NonBootstrapCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !IamS1BootstrapMode.isEnabled(context.getEnvironment());
    }
}
