package com.dataocean.module.audit.service;

import com.dataocean.common.PageVO;
import com.dataocean.module.audit.entity.dto.AuditFailureDTO;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.entity.query.AuditQuery;
import com.dataocean.module.audit.entity.vo.AuditDetailVO;
import com.dataocean.module.audit.entity.vo.AuditRowVO;
import com.dataocean.module.iam.entity.vo.RoleVersionRefVO;

import java.util.List;

/**
 * 历史审计的业务边界，调用者通过明确类型协作。
 */
public interface AuditService {

    /**
     * 原业务事务结束后独立保存当前已认证主体的失败动作，不伪造实际变化。
     */
    void failure(AuditFailureDTO data);

    /**
     * 以本次可信人员身份保存同事务成功审计。
     */
    void record(AuditWriteDTO data);

    /**
     * 以受控初始化主体保存真实初始化审计。
     */
    void bootstrap(AuditWriteDTO data);

    /**
     * 记录由平台任务提交的业务事实，任务模块提供固定的系统主体名称。
     */
    void system(AuditWriteDTO data, String actorName);

    /**
     * 确保关键业务变化已在同一事务保存对应的成功审计。
     */
    void assertRecorded(long actor, String command, String action, String target);

    /**
     * 在当前权限和查询范围内过滤、统计并返回有效分页记录。
     */
    PageVO<AuditRowVO> list(AuditQuery query);

    /**
     * 按当前查看资格读取完整业务详情或真实历史快照。
     */
    AuditDetailVO detail(long id);

    /**
     * 从真实关联目标人员的成功审计读取可核验的历史角色依据。
     */
    List<RoleVersionRefVO> recoveryRoles(long userId, long auditId);
}
