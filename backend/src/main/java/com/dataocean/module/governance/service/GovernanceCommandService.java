package com.dataocean.module.governance.service;

import com.dataocean.module.governance.entity.dto.GovernanceConfirmDTO;
import com.dataocean.module.governance.entity.dto.GovernanceControlDTO;
import com.dataocean.module.governance.entity.dto.GovernanceDraftDTO;
import com.dataocean.module.governance.entity.dto.GovernanceGapDTO;
import com.dataocean.module.governance.entity.dto.GovernanceImpactTargetsDTO;
import com.dataocean.module.governance.entity.vo.GovernanceMutationVO;
import com.dataocean.module.governance.entity.vo.GovernanceNoteChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;

/**
 * 治理草稿、审核、资料定位和使用控制的业务边界。
 */
public interface GovernanceCommandService {

    /**
     * 在当前条目上保存实际业务草稿。
     */
    GovernanceMutationVO draft(GovernanceTarget target, String command, GovernanceDraftDTO input);

    /**
     * 记录待补充的明确业务缺口。
     */
    GovernanceMutationVO supplement(GovernanceTarget target, String command, GovernanceGapDTO input);

    /**
     * 提交已核对的当前定义或已确证移除的处理。
     */
    GovernanceMutationVO confirm(GovernanceTarget target, String command, GovernanceConfirmDTO input);

    /**
     * 关联尚未明确定位的同源资料变化。
     */
    GovernanceNoteChangeVO associate(long source, long change, String command, GovernanceImpactTargetsDTO input);

    /**
     * 核对人工禁止对应的当前实际范围。
     */
    ImpactPreviewVO preview(GovernanceTarget target, GovernanceControlDTO input);

    /**
     * 按实际核对结果提交人工禁止。
     */
    GovernanceMutationVO forbid(GovernanceTarget target, String command, GovernanceControlDTO input);
}
