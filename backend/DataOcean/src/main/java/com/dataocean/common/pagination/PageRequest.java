package com.dataocean.common.pagination;

public final class PageRequest {

    public static final long DEFAULT_PAGE = 1L;
    public static final long DEFAULT_PAGE_SIZE = 20L;
    public static final long MAX_PAGE_SIZE = 100L;

    private PageRequest() {
    }

    public static long page(Number page) {
        if (page == null || page.longValue() < 1) {
            return DEFAULT_PAGE;
        }
        return page.longValue();
    }

    public static long size(Number size) {
        if (size == null || size.longValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size.longValue(), MAX_PAGE_SIZE);
    }
}
