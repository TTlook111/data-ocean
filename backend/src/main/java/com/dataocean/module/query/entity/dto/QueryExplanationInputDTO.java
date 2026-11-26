package com.dataocean.module.query.entity.dto;

import com.dataocean.module.model.service.ModelInvocation;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 解释只接受本次已交付且授权的有限快照。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryExplanationInputDTO {

    /**
     * 调用所固定的受控模型配置及许可。
     */
    @ToString.Exclude
    private ModelInvocation model;

    /**
     * 已执行快照中允许展示的统计范围。
     */
    @ToString.Exclude
    private String scope;

    /**
     * 从可披露快照生成的事实句，序号从 1 开始。
     */
    @ToString.Exclude
    private List<String> facts;
}
