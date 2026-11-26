package com.dataocean.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;

import java.util.List;
import java.util.function.Function;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {

    /**
     * 经过权限及条件过滤后的当前页记录。
     */
    private List<T> items;

    /**
     * 与当前权限及筛选范围一致的数据库记录总数。
     */
    private long total;

    /**
     * 外部请求的页码，从一开始并由公共规则归一。
     */
    private long pageNum;

    /**
     * 外部请求的每页条数，服务端按公共上限保护。
     */
    private long pageSize;

    /**
     * 安全计算页偏移，拒绝数值溢出。
     */
    public static <R, T> PageVO<T> from(IPage<R> page, Function<R, T> convert) {
        if (page.getTotal() > 9007199254740991L) {
            throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "记录总数超出当前列表容量");
        }
        return new PageVO<>(
                page.getRecords().stream().map(convert).toList(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
