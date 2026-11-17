package com.dataocean.module.audit.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.List;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import com.dataocean.module.iam.entity.vo.ChangeVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class AuditDetailVO {
    private String id;
    private AuditActorVO actor;
    private String actionCode;
    private String targetType;
    private String targetId;
    private String targetName;
    private String outcome;
    private Instant occurredAt;
    private String reason;
    private List<ChangeVO> changes;
    private String securityVersion;
    private String commandId;
    private String businessRequestId;
    private List<RoleVersionRefVO> roleSnapshots;
}
