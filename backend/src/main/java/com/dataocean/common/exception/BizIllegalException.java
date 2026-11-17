package com.dataocean.common.exception;

public class BizIllegalException extends CommonException {
    public BizIllegalException(String message) { this(ErrorCode.VALIDATION_FAILED,message); }
    public BizIllegalException(ErrorCode code,String message) { super(code.getCode(),code.getStatus(),message,null); }
    public BizIllegalException(ErrorCode code,String message,Throwable cause) { super(code.getCode(),code.getStatus(),message,cause); }
    public static BizIllegalException invalid(String message) { return new BizIllegalException(ErrorCode.VALIDATION_FAILED,message); }
    public static BizIllegalException denied() { return new BizIllegalException(ErrorCode.PERMISSION_DENIED,"当前没有执行此操作的权限"); }
    public static BizIllegalException missing() { return new BizIllegalException(ErrorCode.RESOURCE_NOT_FOUND,"记录不存在或当前不可访问"); }
    public static BizIllegalException conflict() { return new BizIllegalException(ErrorCode.VERSION_CONFLICT,"内容已变化，请重新读取并核对"); }
    public static BizIllegalException state(String message) { return new BizIllegalException(ErrorCode.STATE_CONFLICT,message); }
}
