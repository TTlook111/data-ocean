package com.dataocean.module.metadata.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dataocean.module.metadata.entity.MetadataChangeEvent;
import com.dataocean.module.metadata.mapper.MetadataChangeEventMapper;
import com.dataocean.module.metadata.service.MetadataChangeEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 元数据变更事件服务实现
 *
 * @author dataocean
 */
@Slf4j
@Service
public class MetadataChangeEventServiceImpl extends ServiceImpl<MetadataChangeEventMapper, MetadataChangeEvent>
        implements MetadataChangeEventService {

    @Override
    public void recordEvent(String eventType, String entityType, Long entityId,
                            String entityFqn, String changeData, Long operatorId) {
        MetadataChangeEvent event = new MetadataChangeEvent();
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setEntityFqn(entityFqn);
        event.setChangeData(changeData);
        event.setOperatorId(operatorId);
        baseMapper.insert(event);
        log.debug("元数据变更事件已记录 type={} entityType={} entityId={} fqn={}",
                eventType, entityType, entityId, entityFqn);
    }
}
