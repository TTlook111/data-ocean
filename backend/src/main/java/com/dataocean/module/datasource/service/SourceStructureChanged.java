package com.dataocean.module.datasource.service;

/**
 * 通知应用内消费者同步投影此次成功采集或事实核实。
 *
 * @param sourceId 数据源编号
 * @param taskId 成功采集任务，人工核实时为空
 * @param changeId 单条已核实变化，采集发布时为空
 */
public record SourceStructureChanged(long sourceId, String taskId, Long changeId) {}
