package com.dataocean.module.access.entity.vo;

import com.dataocean.module.iam.entity.vo.UserOptionVO;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询到的一行数据源审批责任。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResponsibilityVO {

    /**
     * 责任所属数据源。
     */
    private AccessSourceOptionVO source;

    /**
     * 主审批人的当前身份。
     */
    private UserOptionVO primary;

    /**
     * 替代负责人的当前身份。
     */
    private UserOptionVO alternate;

    /**
     * 零表示没有配置。
     */
    private int version;

    /**
     * 责任当前是否可以分派。
     */
    private String state;

    /**
     * 保存时间，UTC。
     */
    private Instant updatedAt;
}
