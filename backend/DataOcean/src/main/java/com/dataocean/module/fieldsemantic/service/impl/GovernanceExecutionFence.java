package com.dataocean.module.fieldsemantic.service.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.mapper.GovernanceTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

/** 只在最终落库时持有领取行锁，不跨模型调用占用事务。 */
@Component @RequiredArgsConstructor
public class GovernanceExecutionFence {
    private final GovernanceTaskMapper tasks;
    private final TransactionTemplate transaction;
    public <T> T persist(Long taskId,String token,Long userId,Supplier<T> operation){
        if(taskId==null&&token==null)return operation.get();
        if(taskId==null||token==null)throw new BusinessException(409,"治理执行凭据不完整");
        return transaction.execute(status->{
            var task=tasks.lock(taskId);
            if(task==null||!Objects.equals(task.getCreatedBy(),userId)||!"AI_RUNNING".equals(task.getStatus())
                    ||!token.equals(task.getExecutionToken())||task.getLockedUntil()==null||!task.getLockedUntil().isAfter(LocalDateTime.now()))
                throw new BusinessException(409,"治理执行已过期或被暂停，旧结果不写入当前候选");
            return operation.get();
        });
    }
}
