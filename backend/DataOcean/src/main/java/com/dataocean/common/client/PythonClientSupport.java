package com.dataocean.common.client;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.exception.PythonRetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.function.Function;

/**
 * Python 服务调用公共支持类。
 * <p>
 * 封装 Python 客户端共用的异常判断、转换和处理逻辑，
 * 避免在各个 Client 实现类中重复相同的代码。
 * </p>
 */
@Slf4j
public final class PythonClientSupport {

    private PythonClientSupport() {
        // 工具类不允许实例化
    }

    /**
     * 将 HTTP 状态码转换为对应的业务异常。
     * <p>
     * 规则：
     * <ul>
     *   <li>5xx 服务端错误 → PythonRetryableException（可重试）</li>
     *   <li>其他错误 → BusinessException（不可重试）</li>
     * </ul>
     * </p>
     *
     * @param statusCode HTTP 状态码
     * @param message    错误描述
     * @return 对应的异常实例
     */
    public static RuntimeException statusException(HttpStatusCode statusCode, String message) {
        if (statusCode.is5xxServerError()) {
            return new PythonRetryableException(message + "，Python 服务返回 " + statusCode.value());
        }
        return new BusinessException(message);
    }

    /**
     * 判断异常是否为读超时。
     * <p>
     * 优先遍历异常链检查 SocketTimeoutException 实例，
     * 作为 fallback 检查异常消息中是否包含 "read timed out"。
     * </p>
     *
     * @param exception 待检查的异常
     * @return 如果是读超时返回 true，否则返回 false
     */
    public static boolean isReadTimeout(Throwable exception) {
        // 遍历异常链，只将明确的读取超时识别为 read timeout。
        // 连接超时也可能是 SocketTimeoutException，但应走可重试/服务不可用路径。
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SocketTimeoutException && hasReadTimeoutMessage(current)) {
                return true;
            }
            current = current.getCause();
        }
        // fallback：检查异常消息
        return hasReadTimeoutMessage(exception);
    }

    /**
     * 判断异常是否为连接失败。
     * <p>
     * 遍历异常链检查 ConnectException 实例，
     * 连接拒绝通常表示服务不可达（应返回 503）。
     * </p>
     *
     * @param exception 待检查的异常
     * @return 如果是连接失败返回 true，否则返回 false
     */
    public static boolean isConnectFailure(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 执行 Python 服务调用，统一异常处理。
     * <p>
     * 使用自定义的异常映射函数，允许调用方根据业务场景定制错误提示。
     * </p>
     *
     * @param <T>                 返回类型
     * @param call                实际的 Python 服务调用
     * @param resourceAccessMapper ResourceAccessException 映射函数（网络异常）
     * @param genericMapper       通用异常映射函数（其他异常）
     * @return 调用结果
     * @throws RuntimeException 映射后的异常
     */
    public static <T> T execute(
            java.util.function.Supplier<T> call,
            Function<ResourceAccessException, RuntimeException> resourceAccessMapper,
            Function<Exception, RuntimeException> genericMapper) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw resourceAccessMapper.apply(e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw genericMapper.apply(e);
        }
    }

    /**
     * 执行 Python 服务调用，使用默认的异常处理逻辑。
     * <p>
     * 默认处理：
     * <ul>
     *   <li>读超时 → BusinessException（不可重试）</li>
     *   <li>连接失败 → PythonRetryableException（可重试）</li>
     *   <li>其他 ResourceAccessException → PythonRetryableException（可重试）</li>
     *   <li>BusinessException → 直接抛出</li>
     *   <li>其他异常 → BusinessException（不可重试）</li>
     * </ul>
     * </p>
     *
     * @param <T>       返回类型
     * @param call      实际的 Python 服务调用
     * @param taskDesc  任务描述（用于错误消息）
     * @return 调用结果
     * @throws RuntimeException 处理后的异常
     */
    public static <T> T executeWithDefaultMapping(java.util.function.Supplier<T> call, String taskDesc) {
        return execute(
                call,
                // ResourceAccessException 映射
                e -> {
                    if (isReadTimeout(e)) {
                        return new BusinessException(taskDesc + "超时，请稍后重试");
                    }
                    return new PythonRetryableException(taskDesc + "服务暂时不可用", e);
                },
                // 通用异常映射
                e -> {
                    log.error("{}失败，reason={}", taskDesc, e.getMessage(), e);
                    return new BusinessException(taskDesc + "失败，请稍后重试");
                }
        );
    }

    private static boolean hasReadTimeoutMessage(Throwable exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("read timed out");
    }
}
