package com.dataocean.module.user.entity.query;

import com.dataocean.common.pagination.PageRequest;
import lombok.Data;

@Data
public class UserQuery {
    private String username;
    private String realName;
    private Long departmentId;
    private Integer status;
    private Long pageNum = 1L;
    private Long page = 1L;
    private Long pageSize = 20L;

    public Long resolvedPage() {
        Long current = page != null ? page : pageNum;
        return PageRequest.page(current);
    }

    public Long resolvedPageSize() {
        return PageRequest.size(pageSize);
    }
}
