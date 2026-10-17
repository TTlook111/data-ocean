package com.dataocean.common.entity.query;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BusinessException;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 公共分页查询条件。业务查询继承本类，只定义筛选条件和可排序字段。
 */
@Data
public class PageQuery {

    public static final long DEFAULT_PAGE_NUM = 1L;
    public static final long DEFAULT_PAGE_SIZE = 20L;
    public static final long MAX_PAGE_SIZE = 100L;

    /** 标准页码；未传入时兼容 page、pageNum，按此顺序取值。 */
    private Long pageNo;
    private Long page;
    private Long pageNum;
    private Long pageSize = DEFAULT_PAGE_SIZE;
    private Boolean isAsc = true;
    private String sortBy;

    public Long getPageNo() {
        return normalizePage(pageNo != null ? pageNo : page != null ? page : pageNum);
    }

    public Long getPageSize() {
        return normalizePageSize(pageSize);
    }

    public static long normalizePage(Number value) {
        return value == null || value.longValue() < 1 ? DEFAULT_PAGE_NUM : value.longValue();
    }

    public static long normalizePageSize(Number value) {
        return value == null || value.longValue() < 1
                ? DEFAULT_PAGE_SIZE : Math.min(value.longValue(), MAX_PAGE_SIZE);
    }

    /** 计算偏移量，拒绝溢出后可能回到第一页的页码。 */
    public long from() {
        try {
            return Math.multiplyExact(getPageNo() - 1, getPageSize());
        } catch (ArithmeticException ex) {
            throw new BusinessException(400, "分页偏移量超出允许范围");
        }
    }

    /** 显式指定的排序项仅供后端代码使用，优先于请求中的排序条件。 */
    public <T> Page<T> toMpPage(OrderItem... orderItems) {
        from();
        Page<T> result = new Page<>(getPageNo(), getPageSize());
        if (orderItems != null && orderItems.length > 0) {
            result.addOrder(orderItems);
        } else if (StringUtils.hasText(sortBy)) {
            result.addOrder(new OrderItem().setColumn(resolveSortColumn()).setAsc(Boolean.TRUE.equals(isAsc)));
        }
        return result;
    }

    /** 默认排序列由后端提供；请求指定排序列时必须经过业务白名单映射。 */
    public <T> Page<T> toMpPage(String defaultSortBy, boolean defaultIsAsc) {
        Page<T> result = toMpPage();
        if (result.orders().isEmpty()) {
            result.addOrder(new OrderItem().setColumn(defaultSortBy).setAsc(defaultIsAsc));
        }
        return result;
    }

    public <T> Page<T> toMpPageDefaultSortByCreatedAtDesc() {
        return toMpPage("created_at", false);
    }

    /** key 为接口允许的字段名，value 为后端固定的数据库列名。 */
    protected Map<String, String> sortColumns() {
        return Map.of();
    }

    private String resolveSortColumn() {
        String column = sortColumns().get(sortBy);
        if (column == null) {
            throw new BusinessException(400, "不支持的排序字段");
        }
        return column;
    }
}
