package com.dataocean.module.permission.s1.service;

import com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO;
import com.dataocean.module.permission.s1.entity.vo.IamS1DataAuthorizationSnapshot;

/** IAM-SIMPLE-1 唯一数据授权计算器；预览直接复用同一 resolve。 */
public interface IamS1DataAuthorizationResolver {

    IamS1DataAuthorizationSnapshot resolve(IamS1DataAuthorizationRequestDTO request);

    default IamS1DataAuthorizationSnapshot preview(IamS1DataAuthorizationRequestDTO request) {
        return resolve(request);
    }
}
