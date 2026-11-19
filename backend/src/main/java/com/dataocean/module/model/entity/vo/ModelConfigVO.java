package com.dataocean.module.model.entity.vo;

/**
 * 当前配置或历史修订的安全信息。
 *
 * @param id 模型 ID，字符串避免前端精度损失
 * @param revisionId 配置修订 ID
 * @param revision 配置内容修订号
 * @param name 管理界面使用的名称
 * @param protocol 接口格式
 * @param endpoint 已配置的服务 URL
 * @param modelName 取用时指定的模型名称
 * @param timeoutSeconds 单次调用上限，单位秒
 * @param enabled 模型当前启用状态
 * @param version 当前状态与内容共用的并发版本
 */
public record ModelConfigVO(
        String id, String revisionId, int revision, String name, String protocol,
        String endpoint, String modelName, int timeoutSeconds, boolean enabled, int version) {
}
