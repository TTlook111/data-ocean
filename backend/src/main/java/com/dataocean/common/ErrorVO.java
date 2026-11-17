package com.dataocean.common;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorVO {

    /**
     * 可披露的字段名与校验提示，不含被拒绝的秘密值。
     */
    private List<FieldErrorVO> fieldErrors;

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 调用者允许再次尝试的等待秒数。
     */
    private Integer retryAfterSeconds;

    /**
     * 公共契约的明确对外视图，只保留调用者可披露字段。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldErrorVO {

        /**
         * 差异或校验错误对应的明确字段路径。
         */
        private String field;

        /**
         * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
         */
        private String code;

        /**
         * 允许向调用者披露的业务结果说明。
         */
        private String message;
    }
}
