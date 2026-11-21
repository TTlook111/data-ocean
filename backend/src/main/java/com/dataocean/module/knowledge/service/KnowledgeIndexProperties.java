package com.dataocean.module.knowledge.service;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * 部署侧明确准入的知识外发范围和代次名称前缀。
 */
@Getter
@Setter
@Component
@ConfigurationProperties("dataocean.knowledge-index")
public class KnowledgeIndexProperties {

    /**
     * 允许向已分配模型发送确认知识正文的源编号。
     */
    private Set<Long> modelSourceIds = Set.of();

    /**
     * 同一 Milvus 中区分不同部署及验收的代次名称前缀。
     */
    private String collectionPrefix = "dataocean_kn";

    /**
     * 仅由服务端分配新代次名称，请求无法指定存储位置。
     */
    public String collection(String build) {
        if (collectionPrefix == null || !collectionPrefix.matches("[a-zA-Z][a-zA-Z0-9_]{0,47}")) {
            // 非法部署配置不得将任务派到不明确的索引位置。
            throw new IllegalStateException("知识索引代次前缀无效");
        }
        return collectionPrefix + "_" + build.replace("-", "");
    }
}
