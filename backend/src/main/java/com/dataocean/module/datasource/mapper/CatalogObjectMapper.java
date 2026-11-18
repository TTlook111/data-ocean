package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.CatalogObjectPO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

/**
 * 保存并查询目录对象的稳定身份。
 */
public interface CatalogObjectMapper extends BaseMapper<CatalogObjectPO> {

    /**
     * 建立新身份并取得数据库生成的主键，首次出现时间取数据库时钟。
     */
    @Insert(
            """
            INSERT INTO ds_object (source_id,generation,source_identity_key,predecessor_object_id,first_seen_at)
            VALUES (#{sourceId},#{generation},#{sourceIdentityKey},#{predecessorObjectId},UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int create(CatalogObjectPO object);

    /**
     * 读取指定来源和对象的身份世代。
     */
    default int generation(long source, long object) {
        return selectOne(Wrappers.<CatalogObjectPO>lambdaQuery()
                        .eq(CatalogObjectPO::getSourceId, source)
                        .eq(CatalogObjectPO::getId, object)
                        .select(CatalogObjectPO::getGeneration))
                .getGeneration();
    }

    /**
     * 记录当前对象身份的首次退役时刻及对应的核实事实。
     */
    default int retire(long source, Long object, long change) {
        return update(Wrappers.<CatalogObjectPO>lambdaUpdate()
                .eq(CatalogObjectPO::getSourceId, source)
                .eq(CatalogObjectPO::getId, object)
                .isNull(CatalogObjectPO::getRetiredAt)
                .set(CatalogObjectPO::getRetiredChangeId, change)
                .setSql("retired_at=UTC_TIMESTAMP(3)"));
    }
}
