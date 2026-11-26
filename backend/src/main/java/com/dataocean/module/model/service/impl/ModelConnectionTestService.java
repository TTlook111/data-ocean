package com.dataocean.module.model.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.model.entity.vo.ModelConnectionTestVO;
import com.dataocean.module.model.mapper.ModelMapper;
import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 用固定合成请求测试已保存的模型，按实际修订保存结果。
 */
@Service
public class ModelConnectionTestService {

    /**
     * 仅接收 Python 已约定的诊断分类。
     */
    private static final Set<String> FAILURES =
            Set.of("MODEL_AUTH", "MODEL_RATE_LIMIT", "MODEL_FORMAT", "MODEL_RESULT_UNKNOWN");

    /**
     * 当前模型修订与受控凭据入口。
     */
    private final ModelRegistry registry;

    /**
     * 连接测试记录的数据访问。
     */
    private final ModelMapper models;

    /**
     * 部署启用内部服务时使用的带身份客户端。
     */
    private final ObjectProvider<RestClient> clients;

    /**
     * 独立选择较长测试超时的内部连接，兼容 AI 服务尚未开启的部署。
     */
    public ModelConnectionTestService(
            ModelRegistry registry,
            ModelMapper models,
            @Qualifier("modelTestPythonClient") ObjectProvider<RestClient> clients) {
        this.registry = registry;
        this.models = models;
        this.clients = clients;
    }

    /**
     * 分配顺序标识后执行测试，阻止旧请求晚完成覆盖更新的结果。
     */
    public ModelConnectionTestVO test(long modelId, long revisionId) {
        var client = clients.getIfAvailable();
        if (client == null) {
            // 尚未部署内部能力时给出可处理的配置状态。
            throw BizIllegalException.state("模型连接测试服务未启用");
        }
        var invocation = registry.connectionTestInvocation(modelId, revisionId);
        long testId = IdWorker.getId();
        String failure = testFailure(client, invocation);
        models.insertConnectionTest(testId, revisionId, failure);
        return latest(modelId, revisionId);
    }

    /**
     * 按详情所展示的修订回读，可空表示尚未测试。
     */
    public ModelConnectionTestVO latest(long modelId, long revisionId) {
        return models.latestConnectionTest(modelId, revisionId);
    }

    /**
     * 将机器响应或通信异常转换成可持久化的受控原因。
     */
    private String testFailure(RestClient client, ModelInvocation invocation) {
        try {
            // 请求始终走已签名、固定基址的 Python 入口。
            var result = client.post()
                    .uri("/internal/v1/models/connection-test")
                    .body(invocation)
                    .retrieve()
                    .body(Probe.class);
            if (result != null && Boolean.TRUE.equals(result.passed()) && result.failureCode() == null) {
                // 响应与成功约束一致才记录通过。
                return null;
            }
            if (result != null
                    && Boolean.FALSE.equals(result.passed())
                    && result.failureCode() != null
                    && FAILURES.contains(result.failureCode())) {
                // 失败原因必须在机器接口约定内。
                return result.failureCode();
            }
            return "MODEL_TEST_UNAVAILABLE";
        } catch (RestClientException failure) {
            // 通信异常可能携带内部报文，仅保存稳定分类。
            return "MODEL_TEST_UNAVAILABLE";
        }
    }

    /**
     * Python 返回的合成请求诊断。
     *
     * @param passed 是否取得预期结构
     * @param failureCode 允许披露的稳定失败原因
     */
    private record Probe(Boolean passed, String failureCode) {}
}
