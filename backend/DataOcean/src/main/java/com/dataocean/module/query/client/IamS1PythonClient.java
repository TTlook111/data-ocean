package com.dataocean.module.query.client;

import java.util.Map;
import java.util.function.Consumer;

/** IAM-SIMPLE-1 独立 Python SSE/取消客户端。 */
public interface IamS1PythonClient {
    void executeAsync(String taskId, Map<String, Object> request, Consumer<String> resultConsumer);

    void cancelTask(String taskId);
}
