package com.dataocean.module.access.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 独立子项补充后的实际申请资料，整单说明继续供其他子项使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestApplicationVO {

    /**
     * 本次补充后的业务用途。
     */
    private String purpose;

    /**
     * 该子项补充后的数据需求。
     */
    private String dataNeeded;

    /**
     * 申请人对办理要求的补充说明。
     */
    private String supplement;
}
