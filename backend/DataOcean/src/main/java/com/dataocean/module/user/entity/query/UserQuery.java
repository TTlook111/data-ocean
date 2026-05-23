package com.dataocean.module.user.entity.query;

import com.dataocean.common.pagination.PageRequest;
import lombok.Data;

/**
 * 用户分页查询条件。
 * <p>
 * 支持按用户名、真实姓名、部门和状态筛选，并兼容 pageNum/page 两种页码字段。
 * </p>
 */
@Data
public class UserQuery {
    private String username;
    private String realName;
    private Long departmentId;
    private Integer status;
    private Long pageNum = 1L;
    private Long page = 1L;
    private Long pageSize = 20L;

    /**
     * 解析最终使用的页码。
     *
     * @return 归一化后的页码
     */
    public Long resolvedPage() {
        Long current = page != null ? page : pageNum;
        return PageRequest.page(current);
    }

    /**
     * 解析最终使用的每页条数。
     *
     * @return 归一化后的每页条数
     */
    public Long resolvedPageSize() {
        return PageRequest.size(pageSize);
    }
}
