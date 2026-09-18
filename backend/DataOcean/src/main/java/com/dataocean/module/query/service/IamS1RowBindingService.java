package com.dataocean.module.query.service;

import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;
import com.dataocean.module.query.entity.dto.IamS1ExecutionBinding;

import java.util.List;

/** 从已通过 S1 Resolver 的授权来源构造一次性参数绑定。 */
public interface IamS1RowBindingService {
    List<IamS1ExecutionBinding> build(IamS1DataAuthorizationSnapshot snapshot);
}
