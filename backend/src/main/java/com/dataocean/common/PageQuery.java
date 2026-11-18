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
     * 根据排序白名单构建分页，实际偏移由插件在页码校正后计算。
     */
    public <T> Page<T> toMpPage(String defaultSortBy, boolean ascending) {
        OrderItem order = new OrderItem().setColumn(defaultSortBy).setAsc(ascending);
        if (StrUtil.isNotBlank(sortBy)) {
            // 外部排序名必须先解析为当前查询允许的列。
            String column = sortColumns().get(sortBy);
            if (column == null) {
                // 无对应列时终止，避免将任意字段拼到 SQL 中。
                throw BizIllegalException.invalid("不支持的排序字段");
            }
            order.setColumn(column).setAsc(Boolean.TRUE.equals(isAsc));
        }
        Page<T> page = toFixedOrderMpPage(order);
        page.setOptimizeCountSql(false);
        return page;
    }

    /**
     * 创建采用服务端固定排序的分页对象，由插件计数后校正页码再计算偏移。
     */
    public <T> Page<T> toFixedOrderMpPage(OrderItem... orders) {
        Page<T> page = new Page<>(getPageNum(), getPageSize());
        page.addOrder(orders);
        return page;
    }

    /**
     * 定义外部排序字段到内部数据库列的白名单，不透传外部 SQL 片段。
     */
    protected Map<String, String> sortColumns() {
        return Map.of();
    }
}
