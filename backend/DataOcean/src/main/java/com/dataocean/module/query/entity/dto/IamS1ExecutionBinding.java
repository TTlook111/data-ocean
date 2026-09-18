package com.dataocean.module.query.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** S1 受保护执行通道中的临时绑定；禁止落库、日志和模型上下文。 */
@Getter
@AllArgsConstructor
public class IamS1ExecutionBinding {
    private final String reference;
    private final String valueType;
    private final Object value;
}
