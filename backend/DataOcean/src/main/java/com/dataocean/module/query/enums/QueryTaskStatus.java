package com.dataocean.module.query.enums;

/**
 * 查询任务状态枚举
 */
public enum QueryTaskStatus {

    /** 处理中 */
    PROCESSING,
    /** 已完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 已取消 */
    CANCELLED,
    /** 超时 */
    TIMEOUT
}
