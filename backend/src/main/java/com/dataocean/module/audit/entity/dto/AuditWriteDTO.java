package com.dataocean.module.audit.entity.dto;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditWriteDTO {
    private String actionCode;
    private String targetType;
    private String targetId;
    private String targetName;
    private String reason;
    private List<ChangeVO> changes;
    private List<RoleVersionRefVO> beforeRoles;
    private List<RoleVersionRefVO> afterRoles;
    private long securityVersion;
    private String commandId;
}
