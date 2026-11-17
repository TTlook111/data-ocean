package com.dataocean.common;

import com.dataocean.common.exception.BizIllegalException;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import cn.hutool.core.util.StrUtil;

@Data
@Accessors(chain = true)
public class PageQuery {
    public static final long DEFAULT_PAGE_NUM = 1L;
    public static final long DEFAULT_PAGE_SIZE = 10L;
    public static final long MAX_PAGE_SIZE = 100L;
    private Long pageNum;
    private Long pageSize;
    private String sortBy;
    private Boolean isAsc = true;
    public Long getPageNum() { return normalizePage(pageNum); }
    public Long getPageSize() { return normalizePageSize(pageSize); }
    public static long normalizePage(Number value) {
        return value == null || value.longValue() < 1 ? DEFAULT_PAGE_NUM : value.longValue();
    }
    public static long normalizePageSize(Number value) {
        return value == null || value.longValue() < 1 ? DEFAULT_PAGE_SIZE : Math.min(value.longValue(), MAX_PAGE_SIZE);
    }
    public long from() {
        try { return Math.multiplyExact(getPageNum() - 1, getPageSize()); }
        catch (ArithmeticException error) { throw BizIllegalException.invalid("分页偏移量超出允许范围"); }
    }
    /** 沿用现有分页类：前端分页参数生效，后端只兜底及保护，不限制为界面上的几个选项。 */
    public <T> Page<T> toMpPage(OrderItem... orders) {
        from();
        Page<T> page = new Page<>(getPageNum(), getPageSize());
        page.setOptimizeCountSql(false);
        if (orders != null && orders.length > 0) page.addOrder(orders);
        else if (StrUtil.isNotBlank(sortBy)) {
            String column = sortColumns().get(sortBy);
            if (column == null) throw BizIllegalException.invalid("不支持的排序字段");
            page.addOrder(new OrderItem().setColumn(column).setAsc(Boolean.TRUE.equals(isAsc)));
        }
        return page;
    }
    public <T> Page<T> toMpPage(String defaultSortBy, boolean ascending) {
        Page<T> page = toMpPage();
        if (page.orders().isEmpty()) page.addOrder(new OrderItem().setColumn(defaultSortBy).setAsc(ascending));
        return page;
    }
    protected Map<String,String> sortColumns() { return Map.of(); }
}
