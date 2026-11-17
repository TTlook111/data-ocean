package com.dataocean.infrastructure.security;

import com.dataocean.common.result.Result;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorWriter {
    private final JsonCodec json;
    public ApiErrorWriter(JsonCodec json) { this.json=json; }
    public void write(HttpServletResponse response,int status,String code,String message) throws IOException {
        write(response,status,ErrorCode.valueOf(code).getCode(),message);
    }
    public void write(HttpServletResponse response,int status,int code,String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write(json.write(new Result<>(code,message,null)));
    }
}
