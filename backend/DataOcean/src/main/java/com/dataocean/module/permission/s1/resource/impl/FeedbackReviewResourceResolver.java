package com.dataocean.module.permission.s1.resource.impl;

import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldtag.entity.UserFeedback;
import com.dataocean.module.fieldtag.mapper.UserFeedbackMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.permission.s1.resource.IamS1ResolvedResource;
import com.dataocean.module.permission.s1.resource.IamS1ResourceResolver;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FEEDBACK_REVIEW：用户反馈审核的真实归属。
 *
 * <p>反馈有两条归属路径，按优先级解析：</p>
 * <ol>
 *   <li>`column_meta_id` → `db_column_meta.datasource_id`（字段级反馈，最精确）；</li>
 *   <li>`query_task_id` → `query_task.datasource_id`（只挂在查询任务上的反馈）。</li>
 * </ol>
 *
 * <p>两条都解析不出归属时 409。字段与查询任务同时存在但 datasource 不一致时也 409，
 * 不能只信其中一侧。不允许因为「解析不到源」就按无限制放行。</p>
 */
@Component
@RequiredArgsConstructor
public class FeedbackReviewResourceResolver implements IamS1ResourceResolver {

    private final UserFeedbackMapper feedbackMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final QueryTaskMapper queryTaskMapper;

    @Override
    public IamS1ResourceType supports() {
        return IamS1ResourceType.FEEDBACK_REVIEW;
    }

    @Override
    public IamS1ResolvedResource resolve(Object resourceId) {
        Long id = IamS1ResourceIds.asLong(resourceId);
        if (id == null) {
            throw new BusinessException(400, "缺少反馈参数，无法判定负责范围");
        }
        UserFeedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new BusinessException(404, "反馈不存在");
        }

        Long columnDatasourceId = null;
        Long snapshotId = null;
        String tableName = null;
        String columnName = null;
        if (feedback.getColumnMetaId() != null) {
            DbColumnMeta column = columnMetaMapper.selectById(feedback.getColumnMetaId());
            if (column == null) {
                throw new BusinessException(409, "反馈关联的字段不存在，无法判定负责范围");
            }
            if (column.getDatasourceId() == null) {
                throw new BusinessException(409, "反馈关联的字段缺少数据源归属，无法判定负责范围");
            }
            columnDatasourceId = column.getDatasourceId();
            snapshotId = column.getSnapshotId();
            tableName = column.getTableName();
            columnName = column.getColumnName();
        }

        Long taskDatasourceId = null;
        if (feedback.getQueryTaskId() != null) {
            QueryTask task = queryTaskMapper.selectById(feedback.getQueryTaskId());
            if (task == null) {
                throw new BusinessException(409, "反馈关联的查询任务不存在，无法判定负责范围");
            }
            if (task.getDatasourceId() == null) {
                throw new BusinessException(409, "反馈关联的查询任务缺少数据源归属，无法判定负责范围");
            }
            taskDatasourceId = task.getDatasourceId();
        }

        if (columnDatasourceId != null && taskDatasourceId != null
                && !columnDatasourceId.equals(taskDatasourceId)) {
            throw new BusinessException(409, "反馈的字段归属与查询任务归属不一致，无法判定负责范围");
        }
        if (columnDatasourceId != null) {
            return new IamS1ResolvedResource(IamS1ResourceType.FEEDBACK_REVIEW, id,
                    columnDatasourceId, snapshotId, tableName, columnName);
        }
        if (taskDatasourceId != null) {
            return new IamS1ResolvedResource(IamS1ResourceType.FEEDBACK_REVIEW, id,
                    taskDatasourceId, null, null, null);
        }

        throw new BusinessException(409, "反馈既没有字段归属也没有任务归属，无法判定负责范围");
    }
}
