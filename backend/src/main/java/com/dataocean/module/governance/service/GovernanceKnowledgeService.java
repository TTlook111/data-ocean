package com.dataocean.module.governance.service;

import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.vo.GovernanceFactOptionVO;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;

/**
 * 知识模块只通过此契约检查治理确认及当前可用性。
 */
public interface GovernanceKnowledgeService {

    /**
     * 按已确认身份分页并给出实际可选择的状态。
     */
    PageVO<GovernanceFactOptionVO> list(Source source, String kind, Long object, PageQuery query);

    /**
     * 依据当前成功目录回读实际确认，失败时不给出可纳入结果。
     */
    GovernanceFactVO require(Source source, GovernanceTarget target);

    /**
     * 仅供回退比对确切治理和结构身份。现行禁止仍由生产门禁执行。
     */
    GovernanceFactVO requireRollbackBasis(Source source, GovernanceTarget target);
}
