package com.dataocean.module.query.event;

/** 只传递身份；受保护来源由已提交的任务记录读取。 */
public record QueryTraceCommittedEvent(String taskId) {}
