package com.dataocean.module.query.entity.dto;

import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.query.service.QueryProtocol.Memory;
import com.dataocean.module.query.service.QueryProtocol.Plan;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 外发模型调用的有界输入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryModelInputDTO {

    /**
     * 调用所固定的受控模型配置及许可。
     */
    @ToString.Exclude
    private ModelInvocation model;

    /**
     * 当前轮次使用的业务问题。
     */
    private String question;

    /**
     * 按当前权限可见的正式知识证据。
     */
    private List<QueryEvidenceSnippetDTO> evidence;

    /**
     * 该轮使用的当前可执行输出规则。
     */
    private List<String> rules;

    /**
     * 独立复核所使用的结构化计划。
     */
    private Plan plan;

    /**
     * 经过权限核对的同源历史上下文。
     */
    private Memory memory;
}
