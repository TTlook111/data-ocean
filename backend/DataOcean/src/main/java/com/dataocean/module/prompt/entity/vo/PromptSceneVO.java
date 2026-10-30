package com.dataocean.module.prompt.entity.vo;

import java.util.List;

/** 真实调用场景及只读合同；不以数据库记录推断消费者。 */
public record PromptSceneVO(String code, String name, String group, String consumer,
                            String contractVersion, List<String> variables,
                            java.util.Map<String, String> variableDescriptions,
                            String outputContract, String fixedConstraints, String metric) { }
