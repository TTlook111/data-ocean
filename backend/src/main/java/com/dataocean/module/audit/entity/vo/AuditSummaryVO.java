package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.entity.vo.ChangeVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditSummaryVO {
    private List<ChangeVO> changes;
    private List<RoleVersionRefVO> roleSnapshots;
}
