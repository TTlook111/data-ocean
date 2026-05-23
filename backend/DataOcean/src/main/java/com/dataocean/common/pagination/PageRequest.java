package com.dataocean.common.pagination;

/**
 * 分页参数归一化工具。
 * <p>
 * 将前端传入的页码和页大小限制在后端允许的范围内，避免无效分页和超大查询。
 * </p>
 */
public final class PageRequest {

    public static final long DEFAULT_PAGE = 1L;
    public static final long DEFAULT_PAGE_SIZE = 20L;
    public static final long MAX_PAGE_SIZE = 100L;

    private PageRequest() {
    }

    /**
     * 解析页码参数。
     *
     * @param page 原始页码
     * @return 有效页码，最小为 {@link #DEFAULT_PAGE}
     */
    public static long page(Number page) {
        if (page == null || page.longValue() < 1) {
            return DEFAULT_PAGE;
        }
        return page.longValue();
    }

    /**
     * 解析每页条数参数。
     *
     * @param size 原始每页条数
     * @return 有效每页条数，最大为 {@link #MAX_PAGE_SIZE}
     */
    public static long size(Number size) {
        if (size == null || size.longValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size.longValue(), MAX_PAGE_SIZE);
    }
}
