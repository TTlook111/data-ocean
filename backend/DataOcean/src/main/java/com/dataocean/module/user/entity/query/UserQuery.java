package com.dataocean.module.user.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 用户分页查询条件。
 * <p>
 * 支持按用户名、真实姓名、部门和状态筛选。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {
    private String username;
    private String realName;
    private Long departmentId;
    private Integer status;

    @Override
    protected Map<String, String> sortColumns() {
        return Map.of("id", "id", "username", "username", "realName", "real_name",
                "status", "status", "createdAt", "created_at", "created_at", "created_at");
    }
}
