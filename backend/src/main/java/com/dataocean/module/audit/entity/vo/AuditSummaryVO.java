package com.dataocean.module.audit.entity.vo;

import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史审计的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditSummaryVO {

    /**
     * 本次实际变化的必要字段差异。
     */
    private List<ChangeVO> changes;

    /**
     * 该历史事实所关联的不可变角色版本引用。
     */
    private List<RoleVersionRefVO> roleSnapshots;
}
