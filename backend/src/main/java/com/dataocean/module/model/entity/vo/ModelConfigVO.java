package com.dataocean.module.model.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前配置或历史修订的安全信息。
 */
@Data
@AllArgsConstructor
public class ModelConfigVO {

    /**
     * 模型 ID，字符串避免前端精度损失。
     */
    private String id;

    /**
     * 配置修订 ID。
     */
    private String revisionId;

    /**
     * 配置内容修订号。
     */
    private int revision;

    /**
     * 管理界面使用的名称。
     */
    private String name;

    /**
     * 接口格式。
     */
    private String protocol;

    /**
     * 已配置的服务 URL。
     */
    private String endpoint;

    /**
     * 取用时指定的模型名称。
     */
    private String modelName;

    /**
     * 单次调用上限，单位秒。
     */
    private int timeoutSeconds;

    /**
     * 模型当前启用状态。
     */
    private boolean enabled;

    /**
     * 当前状态与内容共用的并发版本。
     */
    private int version;
}
