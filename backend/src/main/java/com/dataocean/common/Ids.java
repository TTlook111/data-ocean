package com.dataocean.common;

import com.dataocean.common.exception.BizIllegalException;
public final class Ids {
    private Ids() {}
    public static long parse(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw BizIllegalException.invalid("记录编号不正确");
        try { return Long.parseLong(id); } catch (NumberFormatException error) { throw BizIllegalException.invalid("记录编号超出范围"); }
    }
    public static Long nullable(String id) { return id == null || id.isBlank() ? null : parse(id); }
    public static String text(Long id) { return id == null ? null : id.toString(); }
}
