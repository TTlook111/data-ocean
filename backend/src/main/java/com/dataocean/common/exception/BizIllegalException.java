package com.dataocean.common.exception;

/**
 * 携带已定义业务错误码，交由统一异常处理器返回可理解的失败。
 */
public class BizIllegalException extends CommonException {

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public BizIllegalException(String message) {
        this(ErrorCode.VALIDATION_FAILED, message);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public BizIllegalException(ErrorCode code, String message) {
        super(code.getCode(), code.getStatus(), message, null);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public BizIllegalException(ErrorCode code, String message, Throwable cause) {
        super(code.getCode(), code.getStatus(), message, cause);
    }

    /**
     * 构造客户端字段或组合约束不满足的业务错误。
     */
    public static BizIllegalException invalid(String message) {
        return new BizIllegalException(ErrorCode.VALIDATION_FAILED, message);
    }

    /**
     * 构造当前功能或对象资格不足的明确拒绝。
     */
    public static BizIllegalException denied() {
        return new BizIllegalException(ErrorCode.PERMISSION_DENIED, "当前没有执行此操作的权限");
    }

    /**
     * 构造不泄露额外对象信息的资源不存在错误。
     */
    public static BizIllegalException missing() {
        return new BizIllegalException(ErrorCode.RESOURCE_NOT_FOUND, "记录不存在或当前不可访问");
    }

    /**
     * 构造当前对象版本变化的明确业务错误。
     */
    public static BizIllegalException conflict() {
        return new BizIllegalException(ErrorCode.VERSION_CONFLICT, "内容已变化，请重新读取并核对");
    }

    /**
     * 构造当前业务状态不允许操作的明确错误。
     */
    public static BizIllegalException state(String message) {
        return new BizIllegalException(ErrorCode.STATE_CONFLICT, message);
    }
}
