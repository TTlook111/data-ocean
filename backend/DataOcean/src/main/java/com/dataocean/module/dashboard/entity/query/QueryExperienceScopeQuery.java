package com.dataocean.module.dashboard.entity.query;

import com.dataocean.module.permission.s1.IamS1Constants;

import java.time.LocalDateTime;
import java.util.List;

/** 内部查询范围。来源集合由服务端 IAM 解析，外部请求不能传入来源列表扩大范围。 */
public record QueryExperienceScopeQuery(List<Long> sourceIds, LocalDateTime start, LocalDateTime end,
                                        int bucketMinutes, int targetSeconds) {
    /** MyBatis 按 record 的同名访问器解析参数，协议只能取服务端常量。 */
    public String protocol() {
        return IamS1Constants.PROTOCOL_VERSION;
    }
}
