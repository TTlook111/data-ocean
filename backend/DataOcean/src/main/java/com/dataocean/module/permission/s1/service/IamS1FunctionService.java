package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.IamS1Function;

import java.util.Collection;
import java.util.List;

/** IAM-SIMPLE-1 固定功能目录服务。 */
public interface IamS1FunctionService {

    /** 校验固定目录并展开依赖，返回稳定顺序的 S1 功能记录。 */
    List<IamS1Function> resolveExpanded(Collection<String> functionCodes);
}
