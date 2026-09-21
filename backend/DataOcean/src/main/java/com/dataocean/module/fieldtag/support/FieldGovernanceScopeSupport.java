package com.dataocean.module.fieldtag.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段治理的负责源范围：列表下推与批量写入前的逐项校验。
 *
 * <p>字段标签、可信度和反馈都以 {@code db_column_meta} 为归属入口。
 * 注解只做功能级准入，范围必须在这里落实，不能相信请求里传来的数据源。</p>
 */
@Component
@RequiredArgsConstructor
public class FieldGovernanceScopeSupport {

    public static final String VIEW_FUNCTION = "governance:field:view";
    public static final String MANAGE_FUNCTION = "governance:field:manage";
    public static final int MAX_BATCH_COLUMNS = 200;
    /** CSV 导入文件字节上限，防止只限行数时上传超大文件。 */
    public static final long MAX_CSV_BYTES = 1_048_576L;

    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;
    private final DbColumnMetaMapper columnMetaMapper;

    /** 调用者在指定功能上负责的数据源；空列表表示没有任何负责源。 */
    public List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

    /**
     * 批量读取字段真实归属并逐源校验写入权。
     *
     * <p>任一字段不存在、归属断链或无权，整批拒绝且<strong>零写入</strong>。
     * 必须在任何 insert/update 之前调用。</p>
     */
    public List<DbColumnMeta> requireColumnsWritable(Collection<Long> columnMetaIds) {
        return requireColumns(columnMetaIds, MANAGE_FUNCTION);
    }

    /**
     * 批量读取字段真实归属并逐源校验查看权。
     * 任一字段不存在、归属断链或无权，整批拒绝，不能静默过滤。
     */
    public List<DbColumnMeta> requireColumnsVisible(Collection<Long> columnMetaIds) {
        return requireColumns(columnMetaIds, VIEW_FUNCTION);
    }

    private List<DbColumnMeta> requireColumns(Collection<Long> columnMetaIds, String functionCode) {
        List<Long> distinct = distinctIds(columnMetaIds);
        if (distinct.isEmpty()) {
            throw new BusinessException(400, "字段列表不能为空");
        }
        if (distinct.size() > MAX_BATCH_COLUMNS) {
            throw new BusinessException(400,
                    "单次最多处理 " + MAX_BATCH_COLUMNS + " 个字段，当前: " + distinct.size());
        }
        List<DbColumnMeta> columns = columnMetaMapper.selectBatchIds(distinct);
        Map<Long, DbColumnMeta> byId = columns == null ? Map.of()
                : columns.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(DbColumnMeta::getId, column -> column, (a, b) -> a, LinkedHashMap::new));
        if (byId.size() != distinct.size()) {
            throw new BusinessException(404, "批量操作包含不存在的字段，已整批拒绝");
        }
        Long userId = UserContext.currentUserId();
        List<DbColumnMeta> ordered = new ArrayList<>(distinct.size());
        for (Long columnId : distinct) {
            DbColumnMeta column = byId.get(columnId);
            if (column.getDatasourceId() == null) {
                throw new BusinessException(409, "字段缺少数据源归属，无法判定负责范围");
            }
            adminGuard.requireDatasourceFunction(userId, functionCode, column.getDatasourceId());
            ordered.add(column);
        }
        return ordered;
    }

    /** 显式筛选一个自己无权的数据源时直接 403，不能把无权伪装成空页。 */
    public void rejectExplicitDatasourceOutsideScope(Long datasourceId, Collection<Long> visible) {
        if (datasourceId != null && (visible == null || !visible.contains(datasourceId))) {
            throw new BusinessException(403, "无权查看该数据源的字段治理数据");
        }
    }

    /** 负责源内的字段 ID；空负责源返回空列表，调用方据此返回空页而不是全库。 */
    public List<Long> columnIdsInDatasources(Collection<Long> visibleDatasourceIds) {
        if (visibleDatasourceIds == null || visibleDatasourceIds.isEmpty()) {
            return List.of();
        }
        return columnMetaMapper.selectList(new LambdaQueryWrapper<DbColumnMeta>()
                        .in(DbColumnMeta::getDatasourceId, visibleDatasourceIds)
                        .select(DbColumnMeta::getId))
                .stream()
                .map(DbColumnMeta::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    public static List<Long> distinctIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    public static Set<Long> asSet(Collection<Long> ids) {
        return Set.copyOf(distinctIds(ids));
    }
}
