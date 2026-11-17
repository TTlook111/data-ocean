package com.dataocean.common;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import java.util.function.Function;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {
    private List<T> items;
    private long total;
    private long pageNum;
    private long pageSize;
    public static <R,T> PageVO<T> from(IPage<R> page,Function<R,T> convert) {
        if (page.getTotal()>9007199254740991L) throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED,"记录总数超出当前列表容量");
        return new PageVO<>(page.getRecords().stream().map(convert).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
}
