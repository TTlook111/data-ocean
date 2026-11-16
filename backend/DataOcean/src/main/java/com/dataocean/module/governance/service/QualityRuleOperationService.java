package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.QualityRuleOperationDTO;
import com.dataocean.module.governance.entity.vo.QualityRuleOperationVO;

/** 规则领域的提交、查证与终结接口；保存配置不执行质量检查。 */
public interface QualityRuleOperationService {
    QualityRuleOperationVO execute(QualityRuleOperationDTO request, Long operatorUserId);

    QualityRuleOperationVO get(String operationId, Long operatorUserId);

    QualityRuleOperationVO resolve(String operationId, Long operatorUserId);
}
