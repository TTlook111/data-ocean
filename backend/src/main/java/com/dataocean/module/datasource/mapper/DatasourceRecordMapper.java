package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.DatasourcePO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 执行数据源身份表的查询和更新。
 */
public interface DatasourceRecordMapper extends BaseMapper<DatasourcePO> {

    /**
     * 建立数据源身份，用同一数据库时刻初始化创建和更新时间并回填主键。
     */
    @Insert(
            """
            INSERT INTO ds_source (name,name_key,lifecycle,created_by,created_at,updated_at)
            VALUES (#{row.name},#{nameKey},#{row.lifecycle},#{actor},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int create(@Param("row") DatasourcePO row, @Param("nameKey") byte[] nameKey, @Param("actor") long actor);

    /**
     * 锁定来源当前行，使运行配置切换和采集任务接收串行化。
     */
    @Select("SELECT * FROM ds_source WHERE id=#{id} FOR UPDATE")
    DatasourcePO lock(long id);

    /**
     * 切换生效配置，并根据已确定的范围变化刷新目录版本和陈旧标记。
     */
    @Update(
            """
            UPDATE ds_source SET current_connection_revision_id=#{revision},name=#{name},name_key=#{nameKey},
                version=version+1,catalog_version=catalog_version+IF(#{scopeChanged},1,0),
                scope_changed_at=IF(#{scopeChanged},UTC_TIMESTAMP(3),scope_changed_at),
                stale_since=IF(#{scopeChanged},NULL,stale_since),
                stale_scope_key=IF(#{scopeChanged},NULL,stale_scope_key),updated_at=UTC_TIMESTAMP(3)
            WHERE id=#{source}
            """)
    int configure(
            @Param("source") long source,
            @Param("revision") long revision,
            @Param("name") String name,
            @Param("nameKey") byte[] nameKey,
            @Param("scopeChanged") boolean scopeChanged);

    /**
     * 将已接收任务占位与指定来源绑定。
     */
    default void holdTask(long source, String task) {
        update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .set(DatasourcePO::getActiveTaskId, task));
    }

    /**
     * 只释放仍指向本任务的占位。
     */
    default void releaseTask(long source, String task) {
        update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .eq(DatasourcePO::getActiveTaskId, task)
                .set(DatasourcePO::getActiveTaskId, null));
    }

    /**
     * 保存数据源当前占位的凭据替换操作。
     */
    default int holdCredentialChange(long source, String change) {
        return update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .set(DatasourcePO::getPendingChangeId, change));
    }

    /**
     * 只释放当前操作拥有的凭据替换占位。
     */
    default int releaseCredentialChange(long source, String change) {
        return update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .eq(DatasourcePO::getPendingChangeId, change)
                .set(DatasourcePO::getPendingChangeId, null));
    }

    /**
     * 读取已通过来源边界核验的当前目录版本。
     */
    default int catalogVersion(long source) {
        return selectOne(Wrappers.<DatasourcePO>lambdaQuery()
                        .select(DatasourcePO::getCatalogVersion)
                        .eq(DatasourcePO::getId, source))
                .getCatalogVersion();
    }

    /**
     * 发布当前成功快照时同步更新目录版本、占位和数据源更新时间。
     */
    default int publishSnapshot(long source, long snapshot) {
        return update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .set(DatasourcePO::getCurrentSnapshotId, snapshot)
                .setIncrBy(DatasourcePO::getCatalogVersion, 1)
                .set(DatasourcePO::getActiveTaskId, null)
                .set(DatasourcePO::getStaleSince, null)
                .set(DatasourcePO::getStaleScopeKey, null)
                .setSql("first_success_at=COALESCE(first_success_at,UTC_TIMESTAMP(3))")
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }

    /**
     * 结构核实改变目录投影时同时登记目录版本和数据源更新时间。
     */
    default void advanceCatalogVersion(long source) {
        update(Wrappers.<DatasourcePO>lambdaUpdate()
                .eq(DatasourcePO::getId, source)
                .setIncrBy(DatasourcePO::getCatalogVersion, 1)
                .setSql("updated_at=UTC_TIMESTAMP(3)"));
    }
}
