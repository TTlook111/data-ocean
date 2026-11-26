package com.dataocean.module.access.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按本人或办理范围披露的申请聚合。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessRequestVO {

    /**
     * 稳定申请编号。
     */
    private String id;

    /**
     * 整单办理修订。
     */
    private int version;

    /**
     * 申请来源及实际目标。
     */
    private UserOptionVO applicant;

    /**
     * 申请人当前直属主部门的显示名称。
     */
    private String departmentName;

    /**
     * 业务工作说明。
     */
    private String purpose;

    /**
     * 所需数据说明。
     */
    private String dataNeeded;

    /**
     * 最近一次补充说明。
     */
    private String supplement;

    /**
     * 子项是否要求整组生效。
     */
    private boolean joint;

    /**
     * 首次提交的 UTC 时刻。
     */
    private Instant submittedAt;

    /**
     * 本次身份允许查看的子项。
     */
    private List<RequestItemVO> items;

    /**
     * 整份申请当前可调整且没有实际办理历史。
     */
    private boolean planAvailable;

    /**
     * 有调整资格时用于整单核对的全部来源，审批待办仍按子项展示。
     */
    private List<AccessSourceOptionVO> planSources;
}
