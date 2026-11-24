package com.dataocean.module.query.entity.dto;

import com.dataocean.module.query.service.QueryProtocol.Memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

/**
 * 检索前的问题理解携带当前提问、同源上下文及本节点固定模型的调用或回读信息。
 *
 * @param <T> 首次调用使用获准的配置，回读只使用原配置的修订标识
 */
@Data
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class QueryIntentInputDTO<T> {

    /**
     * 新派发使用有界调用许可，回读只验证原修订的非敏感信息。
     */
    private T model;

    /**
     * 当前用户问题以及同轮已确认的补充内容。
     */
    private String question;

    /**
     * 已重新核验使用资格的同源对话和查询接受时间。
     */
    private Memory memory;
}
