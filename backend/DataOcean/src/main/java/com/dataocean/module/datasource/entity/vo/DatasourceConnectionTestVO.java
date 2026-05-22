package com.dataocean.module.datasource.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据源连接测试结果视图对象
 * <p>
 * 封装一次数据源连接测试的结果信息，包括是否成功、响应时间、
 * 数据库版本号和提示消息。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasourceConnectionTestVO {

    /** 连接是否成功 */
    private Boolean success;
    /** 连接响应时间（毫秒） */
    private Long responseTimeMs;
    /** 数据库服务器版本号 */
    private String serverVersion;
    /** 结果提示消息（成功或失败原因） */
    private String message;
}
