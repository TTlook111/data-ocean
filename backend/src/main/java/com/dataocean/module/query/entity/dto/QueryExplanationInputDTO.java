package com.dataocean.module.query.entity.dto;

import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.query.entity.vo.QueryDataVO;

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
     * 当前轮次使用的业务问题。
     */
    private String question;

    /**
     * 经当前授权复核的有限结果。
     */
    private QueryDataVO result;
}
