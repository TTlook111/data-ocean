package com.dataocean.module.permission.event;

import org.springframework.context.ApplicationEvent;

/**
 * 权限变更事件
 * <p>
 * 在策略创建/更新/删除后发布，由 {@code @TransactionalEventListener(phase=AFTER_COMMIT)}
 * 监听，确保事务提交后才清除权限缓存，避免脏读。
 * </p>
 *
 * @param subjectId    变更涉及的主体 ID（用户/角色/部门）
 * @param datasourceId 变更涉及的数据源 ID
 * @author dataocean
 */
public class PermissionChangedEvent extends ApplicationEvent {

    private final Long subjectId;
    private final Long datasourceId;

    public PermissionChangedEvent(Object source, Long subjectId, Long datasourceId) {
        super(source);
        this.subjectId = subjectId;
        this.datasourceId = datasourceId;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public Long getDatasourceId() {
        return datasourceId;
    }
}
