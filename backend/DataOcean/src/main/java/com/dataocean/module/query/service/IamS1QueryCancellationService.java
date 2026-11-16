package com.dataocean.module.query.service;

/** 交付已持久化的取消意图，不改变任务结果、执行预算或会话轮次。 */
public interface IamS1QueryCancellationService {
    /** 调用方必须在终态更新的同一事务写入 PENDING；提交后再尽力投递。 */
    void dispatchAfterCommit(String taskId);

    /** 限批补投仍为 PENDING 的取消意图，进程重启后沿用数据库记录。 */
    void retryPending();
}
