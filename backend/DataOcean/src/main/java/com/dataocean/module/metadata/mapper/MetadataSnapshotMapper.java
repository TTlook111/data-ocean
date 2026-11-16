package com.dataocean.module.metadata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dataocean.module.metadata.entity.po.MetadataSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import com.dataocean.common.exception.BusinessException;

/**
 * 元数据快照 Mapper 接口。
 * <p>
 * 继承 MyBatis-Plus BaseMapper，提供元数据快照表的基础 CRUD 操作。
 * </p>
 */
@Mapper
public interface MetadataSnapshotMapper extends BaseMapper<MetadataSnapshot> {
    /** 发布指针属于来源。先锁应用库来源行，再读门禁或更新阻断事实；不锁业务库。 */
    @Select("SELECT id FROM datasource WHERE id = #{datasourceId} AND deleted = 0 FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Long selectReleaseSourceForUpdate(Long datasourceId);

    @Select("SELECT datasource_id FROM metadata_snapshot WHERE id = #{snapshotId}")
    Long releaseSourceId(Long snapshotId);

    default void lockReleaseSource(Long datasourceId) {
        if (datasourceId == null || selectReleaseSourceForUpdate(datasourceId) == null) {
            throw new BusinessException(404, "快照所属数据源不存在");
        }
    }

    /** 请求只提供快照 ID，来源从实际快照解析。锁后清除 MyBatis 事务缓存，再读当前事实。 */
    default void lockReleaseSourceForSnapshot(Long snapshotId) {
        lockReleaseSource(releaseSourceId(snapshotId));
    }
}
