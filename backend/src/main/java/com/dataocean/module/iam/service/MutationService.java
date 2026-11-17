package com.dataocean.module.iam.service;

import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.CommandStatusVO;
import java.util.function.Supplier;

public interface MutationService {
    MutationVO execute(String operation,String command,String target,Object payload,Integer expectedVersion,boolean authorizationChange,boolean allowInitialPassword,Supplier<MutationVO> action);
    long auditSecurityVersion(boolean authorizationChange);
    void invalidateAuthentication(long userId,String reason);
    CommandStatusVO status(String command,String operation);
}
