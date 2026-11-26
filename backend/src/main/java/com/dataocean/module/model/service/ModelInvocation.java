package com.dataocean.module.model.service;

/**
 * 只供当次工作者在回查资格后读取的调用配置。
 *
 * @param revisionId 当次固定配置修订 ID
 * @param protocol 接口格式
 * @param endpoint 服务基址
 * @param modelName 服务端选择的模型名称
 * @param timeoutSeconds 剩余任务与配置上限共同约束的秒数
 * @param apiKey 仅在受认证的调用层消费的模型凭据
 */
public record ModelInvocation(
        String revisionId, String protocol, String endpoint, String modelName, int timeoutSeconds, String apiKey) {

    /**
     * 诊断时只记录版本和协议。
     */
    @Override
    public String toString() {
        return "ModelInvocation[revisionId=" + revisionId + ", protocol=" + protocol + "]";
    }
}
