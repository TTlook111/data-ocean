package com.dataocean.module.query.client;

import java.util.Map;
import java.util.function.Consumer;

/** IAM-SIMPLE-1 独立 Python SSE/取消客户端。 */
public interface IamS1PythonClient {
    void executeAsync(String taskId, Map<String, Object> request, Consumer<String> resultConsumer,
                      Consumer<Map<String, Object>> progressConsumer);

    /** 核验同任务的取消接收回执；投递失败抛出异常，接收成功不代表执行已停止。 */
    void cancelTask(String taskId);
}
