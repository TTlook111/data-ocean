package com.dataocean.module.fieldsemantic.entity.po;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 治理操作不可变证据，不保存业务查询结果行或凭据。 */
@Data @TableName("governance_audit_event")
public class GovernanceAuditEvent {
    @TableId(type=IdType.AUTO) private Long id;
    private String objectType;
    private Long objectId;
    private String action;
    private String payloadJson;
    private Long operatorId;
    private LocalDateTime createdAt;
}
