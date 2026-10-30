package com.dataocean.common.exception;

import lombok.Getter;

import java.util.List;

/**
 * IAM-SIMPLE-1 结果脱敏策略冲突异常
 * <p>
 * 同一输出列（按 {@code outputColumn} 小写规范化后）关联了多个不同的非空脱敏策略时，
 * 由于最终脱敏以列名为键，无论取哪一个都会用错误策略处理另一部分数据。
 * Java 是最终保护边界，不能依赖 Python 侧的校验结果，必须独立判定并 fail-closed：
 * 任务完成阶段拒绝落库，任务/历史读取阶段拒绝返回。
 * </p>
 */
@Getter
public class IamS1MaskPolicyConflictException extends RuntimeException {

    /** 冲突的输出列（小写规范化后的列名与策略明细） */
    private final List<String> conflicts;

    /**
     * 构造脱敏策略冲突异常
     *
     * @param message   面向调用方的说明
     * @param conflicts 冲突明细，每项形如 {@code x -> EMAIL, PHONE}
     */
    public IamS1MaskPolicyConflictException(String message, List<String> conflicts) {
        super(message);
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
