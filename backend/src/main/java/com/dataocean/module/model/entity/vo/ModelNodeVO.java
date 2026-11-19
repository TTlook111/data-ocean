package com.dataocean.module.model.entity.vo;

/**
 * 已接入的业务节点及其独立分配状态。
 *
 * @param code 稳定节点编码
 * @param modelId 当前选择，未分配时为空
 * @param version 节点并发版本
 */
public record ModelNodeVO(String code, String modelId, int version) {
}
