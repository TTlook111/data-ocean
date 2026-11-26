package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与授予的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class GrantLimitVO {

    /**
     * 当前角色的稳定标识，不按角色名称引用。
     */
    private String roleId;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 显式可授予功能集合，与本人使用权分别维护。
     */
    private List<String> grantablePermissionCodes;
}
