package com.dataocean.module.audit.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.entity.query.AuditQuery;
import com.dataocean.module.audit.entity.vo.AuditDetailVO;
import com.dataocean.module.audit.entity.vo.AuditRowVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;
import java.util.List;

public interface AuditService {
    void record(AuditWriteDTO data);
    void bootstrap(AuditWriteDTO data);
    void assertRecorded(long actor,String command,String action,String target);
    PageVO<AuditRowVO> list(AuditQuery query);
    AuditDetailVO detail(long id);
    List<RoleVersionRefVO> recoveryRoles(long userId,long auditId);
}
