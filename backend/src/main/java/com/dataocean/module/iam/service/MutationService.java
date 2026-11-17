package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.vo.CommandStatusVO;
import com.dataocean.module.iam.entity.vo.MutationVO;

import java.util.function.Supplier;

/**
 * 公共契约的业务边界，调用者通过明确类型协作。
 */
public interface MutationService {

    /**
     * 在可信身份、当前能力、命令防重和审计约束下原子执行业务动作。
     */
    MutationVO execute(
            String operation,
            String command,
            String target,
            Object payload,
            Integer expectedVersion,
            boolean authorizationChange,
            boolean allowInitialPassword,
            Supplier<MutationVO> action);

    /**
     * 按当前事务是否改变授权计算应记录的安全修订。
     */
    long auditSecurityVersion(boolean authorizationChange);

    /**
     * 登记认证失效的可靠事件，权威版本校验不等待事件送达。
     */
    void invalidateAuthentication(long userId, String reason);

    /**
     * 查询原主体本次命令的可披露提交事实，并重核当前动作资格。
     */
    CommandStatusVO status(String command, String operation);
}
