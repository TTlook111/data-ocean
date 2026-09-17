package com.dataocean.module.permission.s1.entity.vo;

import lombok.Getter;

/** IAM-SIMPLE-1 管理能力判定结果，拒绝时保留稳定安全原因码。 */
@Getter
public class IamS1AuthorizationDecision {
    private final boolean allowed;
    private final String reasonCode;
    private final String functionCode;
    private final Long datasourceId;

    private IamS1AuthorizationDecision(boolean allowed, String reasonCode, String functionCode,
                                       Long datasourceId) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
        this.functionCode = functionCode;
        this.datasourceId = datasourceId;
    }

    public static IamS1AuthorizationDecision allow(String functionCode, Long datasourceId) {
        return new IamS1AuthorizationDecision(true, "ALLOWED", functionCode, datasourceId);
    }

    public static IamS1AuthorizationDecision deny(String reasonCode, String functionCode, Long datasourceId) {
        return new IamS1AuthorizationDecision(false, reasonCode, functionCode, datasourceId);
    }
}
