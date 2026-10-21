package com.dataocean.module.glossary.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dataocean.module.glossary.entity.query.TermFieldQuery;
import com.dataocean.module.glossary.entity.vo.TermColumnVO;
import com.dataocean.module.metadata.entity.po.DbTableMeta;
import java.util.List;

public interface GlossaryFieldService {
    List<java.util.Map<String, Object>> sources(java.util.Set<Long> sourceIds);
    TermColumnVO resolve(Long entityId);
    List<DbTableMeta> tables(Long datasourceId);
    IPage<TermColumnVO> columns(TermFieldQuery query);
}
