package com.dataocean.module.query.entity.dto;

import com.dataocean.module.query.service.QueryProtocol.Memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

/**
 * 历史摘要与问题理解共用本轮提问、获准上下文和节点调用或回读身份。
 *
 * @param <T> 首次调用使用获准的配置，回读只使用原配置的修订标识
 */
@Data
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class QueryContextInputDTO<T> {

    /**
     * 新派发使用有界调用许可，回读只验证原修订的非敏感信息。
     */
    private T model;

    /**
     * 当前用户问题。理解阶段可包含同轮已做的补充。
     */
    private String question;

    /**
     * 已重新核验使用资格的同源对话和查询接受时间。
     */
    private Memory memory;
}
