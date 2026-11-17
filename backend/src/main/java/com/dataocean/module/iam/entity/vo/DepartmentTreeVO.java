package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 部门与成员的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class DepartmentTreeVO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private String code;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 正常、停用或归档的持久生命周期状态。
     */
    private String lifecycle;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;

    /**
     * 筛选时该节点是否直接命中，补回的祖先为否。
     */
    private boolean matched;

    /**
     * 当前节点的直属子节点，顺序由服务端确定。
     */
    private List<DepartmentTreeVO> children;

    /**
     * 本部门及所有后代范围按人员去重的当前成员数。
     */
    private int totalMemberCount;
}
