package com.dataocean.module.iam.security;

/**
 * 对需要根据请求动作或用途核权的接口提供进入业务前的检查。
 */
public interface RequestPermission {

    /**
     * 使用 MVC 已绑定的入参核权，拒绝时抛出对应业务异常。
     */
    void require(Object[] arguments);
}
