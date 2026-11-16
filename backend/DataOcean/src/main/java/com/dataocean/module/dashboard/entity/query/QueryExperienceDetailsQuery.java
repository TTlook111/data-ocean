package com.dataocean.module.dashboard.entity.query;

import com.dataocean.common.entity.query.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/** 明细带上总览给出的时间锚点；分页及固定排序均沿用当前业务域授权。 */
@Getter
@Setter
public class QueryExperienceDetailsQuery extends PageQuery {
    private String period = "today";
    private String kind;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime asOf;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime bucketStart;
}
