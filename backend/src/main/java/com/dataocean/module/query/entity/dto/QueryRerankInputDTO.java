package com.dataocean.module.query.entity.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

/**
 * 同一检索表述及当前业务资格复核过的原候选。
 *
 * @param <T> 已获准的单次模型配置或无凭据的回读引用
 */
@Data
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class QueryRerankInputDTO<T> {

    /**
     * 固定修订及当次外发所需信息。
     */
    private T model;

    /**
     * 实际向量召回所用的本轮检索表述。
     */
    private String query;

    /**
     * 已获权的发布正文，保留原召回顺序作为位置依据。
     */
    private List<String> documents;
}
