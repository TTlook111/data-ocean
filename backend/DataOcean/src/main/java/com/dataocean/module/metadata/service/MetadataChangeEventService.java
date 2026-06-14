package com.dataocean.module.metadata.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dataocean.module.metadata.entity.MetadataChangeEvent;

/**
 * 元数据变更事件服务接口
 *
 * @author dataocean
 */
public interface MetadataChangeEventService extends IService<MetadataChangeEvent> {

    /**
     * 记录元数据变更事件
     *
     * @param eventType  事件类型（CREATE/UPDATE/DELETE/PUBLISH）
     * @param entityType 实体类型（TABLE/COLUMN/GLOSSARY_TERM/TAG）
     * @param entityId   实体 ID
     * @param entityFqn  实体全限定名
     * @param changeData 变更数据（JSON）
     * @param operatorId 操作人 ID
     */
    void recordEvent(String eventType, String entityType, Long entityId,
                     String entityFqn, String changeData, Long operatorId);
}
