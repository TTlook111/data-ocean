package com.dataocean.module.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dataocean.module.datasource.entity.po.CollectionExecutionPO;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 访问采集执行状态及会话证据。
 */
public interface CollectionExecutionMapper extends BaseMapper<CollectionExecutionPO> {

    /**
     * 在源端开始读取前保存连接证据和数据库登记时间。
     */
    @Insert(
            """
            INSERT INTO ds_collection_execution (id,task_id,run_version,source_instance_key,adapter_version,
                locator_envelope,state,registered_at)
            VALUES (#{id},#{task},#{runVersion},#{sourceKey},'1',#{envelope},'REGISTERED',UTC_TIMESTAMP(3))
            """)
    int register(
            @Param("id") String id,
            @Param("task") String task,
            @Param("runVersion") int runVersion,
            @Param("sourceKey") byte[] sourceKey,
            @Param("envelope") byte[] envelope);

    /**
     * 只登记首次确认的关闭证据，保留已停止记录的原时刻。
     */
    default int stopped(String id) {
        return update(Wrappers.<CollectionExecutionPO>lambdaUpdate()
                .eq(CollectionExecutionPO::getId, id)
                .ne(CollectionExecutionPO::getState, "STOPPED")
                .set(CollectionExecutionPO::getState, "STOPPED")
                .setSql("last_verified_at=UTC_TIMESTAMP(3),stopped_at=UTC_TIMESTAMP(3),"
                        + "stop_proof_kind='SOURCE_SESSION_CLOSED'"));
    }

    /**
     * 记录当前任务尚未证实关闭的执行会话，供后续核实。
     */
    default int uncertain(String task) {
        return update(Wrappers.<CollectionExecutionPO>lambdaUpdate()
                .eq(CollectionExecutionPO::getTaskId, task)
                .ne(CollectionExecutionPO::getState, "STOPPED")
                .set(CollectionExecutionPO::getState, "UNKNOWN")
                .setSql("last_verified_at=UTC_TIMESTAMP(3)"));
    }
}
