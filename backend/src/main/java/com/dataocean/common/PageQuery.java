package com.dataocean.common;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BizIllegalException;

import java.util.Map;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 公共契约的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
@Accessors(chain = true)
public class PageQuery {

    /**
     * 缺省页码，从第一页开始。
     */
    public static final long DEFAULT_PAGE_NUM = 1L;

    /**
     * 未指定时的每页记录数。
     */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    /**
     * 单次数据库分页允许读取的记录上限。
     */
    public static final long MAX_PAGE_SIZE = 100L;

    /**
     * 外部请求的页码，从一开始并由公共规则归一。
     */
    private Long pageNum;

    /**
     * 外部请求的每页条数，服务端按公共上限保护。
     */
    private Long pageSize;

    /**
     * 外部排序字段名，只能映射到服务端白名单列。
     */
    private String sortBy;

    /**
     * 所选白名单排序字段是否按升序排列。
     */
    private Boolean isAsc = true;

    /**
     * 读取归一后的有效页码，默认从第一页开始。
     */
    public Long getPageNum() {
        return normalizePage(pageNum);
    }

    /**
     * 读取归一后的单页条数，继续执行公共上限保护。
     */
    public Long getPageSize() {
        return normalizePageSize(pageSize);
    }

    /**
     * 按已确认分页复用规则归一未填写或无效页码。
     */
    public static long normalizePage(Number value) {
        return value == null || value.longValue() < 1 ? DEFAULT_PAGE_NUM : value.longValue();
    }

    /**
     * 按已确认分页规则归一单页条数并应用公共上限。
     */
    public static long normalizePageSize(Number value) {
        return value == null || value.longValue() < 1 ? DEFAULT_PAGE_SIZE : Math.min(value.longValue(), MAX_PAGE_SIZE);
    }

    /**
     * 安全计算页偏移，拒绝数值溢出。
     */
    public long from() {
        try {
            return Math.multiplyExact(getPageNum() - 1, getPageSize());
        } catch (ArithmeticException error) {
            throw BizIllegalException.invalid("分页偏移量超出允许范围");
        }
    }

    /**
     * 沿用现有分页类：前端分页参数生效，后端只兜底及保护，不限制为界面上的几个选项。
     */
    public <T> Page<T> toMpPage(OrderItem... orders) {
        from();
        Page<T> page = new Page<>(getPageNum(), getPageSize());
        page.setOptimizeCountSql(false);
        if (orders != null && orders.length > 0) {
            page.addOrder(orders);
        } else if (StrUtil.isNotBlank(sortBy)) {
            String column = sortColumns().get(sortBy);
            if (column == null) {
                throw BizIllegalException.invalid("不支持的排序字段");
            }
            page.addOrder(new OrderItem().setColumn(column).setAsc(Boolean.TRUE.equals(isAsc)));
        }
        return page;
    }

    /**
     * 复用分页参数、单页上限和白名单排序构造数据库分页对象。
     */
    public <T> Page<T> toMpPage(String defaultSortBy, boolean ascending) {
        Page<T> page = toMpPage();
        if (page.orders().isEmpty()) {
            page.addOrder(new OrderItem().setColumn(defaultSortBy).setAsc(ascending));
        }
        return page;
    }

    /**
     * 定义外部排序字段到内部数据库列的白名单，不透传外部 SQL 片段。
     */
    protected Map<String, String> sortColumns() {
        return Map.of();
    }
}
