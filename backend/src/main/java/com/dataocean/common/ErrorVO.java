package com.dataocean.common;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorVO {
    private List<FieldErrorVO> fieldErrors;
    private String commandId;
    private Integer retryAfterSeconds;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldErrorVO {
        private String field;
        private String code;
        private String message;
    }
}
