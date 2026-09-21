package com.dataocean.module.metadata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.pagination.PageRequest;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.aspect.AdminAuditLog;
import com.dataocean.module.datasource.entity.Datasource;
import com.dataocean.module.datasource.mapper.DatasourceMapper;
import com.dataocean.module.metadata.entity.DbColumnMeta;
import com.dataocean.module.metadata.entity.DbTableMeta;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.entity.SchemaSyncTask;
import com.dataocean.module.metadata.entity.dto.SyncTriggerDTO;
import com.dataocean.module.metadata.entity.vo.SchemaDiffVO;
import com.dataocean.module.metadata.entity.vo.SnapshotVO;
import com.dataocean.module.metadata.entity.vo.SyncTaskVO;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.metadata.mapper.DbTableMetaMapper;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.metadata.mapper.SchemaSyncTaskMapper;
import com.dataocean.module.metadata.service.SchemaCollectionService;
import com.dataocean.module.metadata.service.SchemaDiffService;
import com.dataocean.module.permission.s1.annotation.IamS1Resource;
import com.dataocean.module.permission.s1.annotation.IamS1ScopedList;
import com.dataocean.module.permission.s1.resource.IamS1ResourceType;
import com.dataocean.module.permission.s1.entity.vo.IamS1DatasourceRefVO;
import com.dataocean.module.permission.s1.service.IamS1CapabilityService;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 元数据采集管理控制器。
 * <p>
 * 提供同步任务触发、同步任务查询、快照列表、快照详情和快照差异对比接口。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/metadata")
@RequiredArgsConstructor
@Slf4j
@AdminAuditLog(logReads = false)
public class MetadataCollectionController {

    private final SchemaCollectionService collectionService;
    private final SchemaDiffService diffService;
    private final SchemaSyncTaskMapper syncTaskMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final DatasourceMapper datasourceMapper;
    private final IamS1AdminGuard adminGuard;
    private final IamS1CapabilityService capabilityService;

    /** 查看采集任务：与“执行采集”拆开，同源负责范围校验。 */
    private static final String COLLECT_VIEW_FUNCTION = "metadata:collect:view";
    /** 执行采集：独立动作码。 */
    private static final String COLLECT_RUN_FUNCTION = "metadata:collect:run";
    /** 读取资产结构与快照。 */
    private static final String VIEW_FUNCTION = "metadata:view";

    /** 调用者在指定功能上负责的数据源 ID。 */
    private List<Long> visibleDatasourceIds(Long userId, String functionCode) {
        return capabilityService.responsibleDatasourcesWithFunction(userId, functionCode)
                .stream()
                .map(IamS1DatasourceRefVO::id)
                .toList();
    }

    /**
     * 手动触发元数据同步任务。
     *
     * @param request 同步触发请求
     * @return 新建同步任务 ID
     */
    @PostMapping("/sync")
    @IamS1Resource(function = COLLECT_RUN_FUNCTION, resourceType = IamS1ResourceType.DATASOURCE, resourceIds = "#request.datasourceId")
    public Result<Map<String, Long>> triggerSync(@Valid @RequestBody SyncTriggerDTO request) {
        Long taskId = collectionService.executeFullSync(request.getDatasourceId(), request.getIncludeStatistics());
        return Result.success("同步任务已触发", Map.of("taskId", taskId));
    }

    /**
     * 分页查询元数据同步任务。
     *
     * @param datasourceId 可选数据源 ID
     * @param page         页码
     * @param size         每页条数
     * @return 同步任务分页列表
     */
    @GetMapping("/sync-tasks")
    @IamS1ScopedList(COLLECT_VIEW_FUNCTION)
    public Result<Page<SyncTaskVO>> listSyncTasks(@RequestParam(required = false) Long datasourceId,
                                                   @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "20") Integer size) {
        Long userId = UserContext.currentUserId();
        // 负责源下推到 SQL：取回后过滤会让 LIMIT/OFFSET 在过滤前生效，分页总数会错。
        List<Long> visible = visibleDatasourceIds(userId, COLLECT_VIEW_FUNCTION);
        if (datasourceId != null) {
            adminGuard.requireDatasourceFunction(userId, COLLECT_VIEW_FUNCTION, datasourceId);
        }
        List<Long> scope = datasourceId != null ? List.of(datasourceId) : visible;
        if (scope.isEmpty()) {
            return Result.success(new Page<>(PageRequest.page(page), PageRequest.size(size), 0));
        }
        Page<SchemaSyncTask> pageParam = new Page<>(PageRequest.page(page), PageRequest.size(size));
        LambdaQueryWrapper<SchemaSyncTask> wrapper = new LambdaQueryWrapper<SchemaSyncTask>()
                .in(SchemaSyncTask::getDatasourceId, scope)
                .orderByDesc(SchemaSyncTask::getCreatedAt);
        Page<SchemaSyncTask> result = syncTaskMapper.selectPage(pageParam, wrapper);

        Map<Long, String> dsNames = getDatasourceNames();
        Page<SyncTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(t -> toSyncTaskVO(t, dsNames)).toList());
        return Result.success(voPage);
    }

    /**
     * 分页查询元数据快照。
     *
     * @param datasourceId 可选数据源 ID
     * @param page         页码
     * @param size         每页条数
     * @return 快照分页列表
     */
    @GetMapping("/snapshots")
    @IamS1ScopedList(VIEW_FUNCTION)
    public Result<Page<SnapshotVO>> listSnapshots(@RequestParam(required = false) Long datasourceId,
                                                   @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "20") Integer size) {
        Long userId = UserContext.currentUserId();
        List<Long> visible = visibleDatasourceIds(userId, VIEW_FUNCTION);
        if (datasourceId != null) {
            adminGuard.requireDatasourceFunction(userId, VIEW_FUNCTION, datasourceId);
        }
        List<Long> scope = datasourceId != null ? List.of(datasourceId) : visible;
        if (scope.isEmpty()) {
            return Result.success(new Page<>(PageRequest.page(page), PageRequest.size(size), 0));
        }
        Page<MetadataSnapshot> pageParam = new Page<>(PageRequest.page(page), PageRequest.size(size));
        LambdaQueryWrapper<MetadataSnapshot> wrapper = new LambdaQueryWrapper<MetadataSnapshot>()
                .in(MetadataSnapshot::getDatasourceId, scope)
                .orderByDesc(MetadataSnapshot::getCreatedAt);
        Page<MetadataSnapshot> result = snapshotMapper.selectPage(pageParam, wrapper);

        Map<Long, String> dsNames = getDatasourceNames();
        Page<SnapshotVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(s -> toSnapshotVO(s, dsNames)).toList());
        return Result.success(voPage);
    }

    /**
     * 查询快照详情。
     *
     * @param id 快照 ID
     * @return 快照、表和字段元数据详情
     */
    @GetMapping("/snapshots/{id}")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#id")
    public Result<Map<String, Object>> getSnapshotDetail(@PathVariable Long id) {
        MetadataSnapshot snapshot = snapshotMapper.selectById(id);
        if (snapshot == null) {
            return Result.error(404, "快照不存在");
        }

        List<DbTableMeta> tables = tableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>().eq(DbTableMeta::getSnapshotId, id));
        List<DbColumnMeta> columns = columnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>().eq(DbColumnMeta::getSnapshotId, id));

        Map<String, Object> detail = new HashMap<>();
        detail.put("snapshot", snapshot);
        Datasource datasource = datasourceMapper.selectById(snapshot.getDatasourceId());
        detail.put("datasourceName", datasource == null ? null : datasource.getName());
        detail.put("tables", tables);
        detail.put("columns", columns);
        return Result.success(detail);
    }

    /**
     * 查询快照中的表元数据。
     *
     * @param id 快照 ID
     * @return 表元数据列表
     */
    @GetMapping("/snapshots/{id}/tables")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#id")
    public Result<List<DbTableMeta>> listSnapshotTables(@PathVariable Long id) {
        List<DbTableMeta> tables = tableMetaMapper.selectList(
                new LambdaQueryWrapper<DbTableMeta>()
                        .eq(DbTableMeta::getSnapshotId, id)
                        .orderByAsc(DbTableMeta::getTableName));
        return Result.success(tables);
    }

    /**
     * 查询快照中指定表的字段元数据。
     *
     * @param id        快照 ID
     * @param tableName 表名
     * @return 字段元数据列表
     */
    @GetMapping("/snapshots/{id}/tables/{tableName}/columns")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = "#id")
    public Result<List<DbColumnMeta>> listSnapshotTableColumns(@PathVariable Long id,
                                                                @PathVariable String tableName) {
        List<DbColumnMeta> columns = columnMetaMapper.selectList(
                new LambdaQueryWrapper<DbColumnMeta>()
                        .eq(DbColumnMeta::getSnapshotId, id)
                        .eq(DbColumnMeta::getTableName, tableName)
                        .orderByAsc(DbColumnMeta::getOrdinalPosition));
        return Result.success(columns);
    }

    /**
     * 对比两个元数据快照的结构差异（只读，不写入变更事件，可安全重复调用）。
     *
     * @param oldId 旧快照 ID
     * @param newId 新快照 ID
     * @return 快照差异结果
     */
    @GetMapping("/snapshots/diff")
    @IamS1Resource(function = VIEW_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = {"#oldId", "#newId"})
    public Result<SchemaDiffVO> diffSnapshots(@RequestParam Long oldId, @RequestParam Long newId) {
        Long userId = UserContext.currentUserId();
        // 两个快照都要校验：只校验其中一个就能借无权快照读到无权数据源的差异。
        return Result.success(diffService.compareSnapshots(oldId, newId));
    }

    /**
     * 对比两个快照并将差异持久化为变更事件（幂等，显式触发）。
     * <p>
     * 写入前会清除同一快照对的历史事件，因此重复触发不会产生重复记录。
     * </p>
     *
     * @param oldId 旧快照 ID
     * @param newId 新快照 ID
     * @return 快照差异结果
     */
    @PostMapping("/snapshots/diff/record")
    @IamS1Resource(function = COLLECT_RUN_FUNCTION, resourceType = IamS1ResourceType.SNAPSHOT, resourceIds = {"#oldId", "#newId"})
    public Result<SchemaDiffVO> recordSnapshotDiff(@RequestParam Long oldId, @RequestParam Long newId) {
        Long userId = UserContext.currentUserId();
        return Result.success("变更事件已记录", diffService.compareAndRecordChanges(oldId, newId));
    }

    /**
     * 将同步任务实体转换为视图对象。
     *
     * @param task    同步任务实体
     * @param dsNames 数据源ID到名称的映射，用于填充数据源名称
     * @return 同步任务视图对象
     */
    private SyncTaskVO toSyncTaskVO(SchemaSyncTask task, Map<Long, String> dsNames) {
        SyncTaskVO vo = new SyncTaskVO();
        vo.setId(task.getId());
        vo.setDatasourceId(task.getDatasourceId());
        vo.setDatasourceName(dsNames.getOrDefault(task.getDatasourceId(), "未知"));  // 从映射中获取数据源名称
        vo.setTriggerType(task.getTriggerType());  // 触发类型（MANUAL/SCHEDULED）
        vo.setStatus(task.getStatus());            // 任务状态（RUNNING/SUCCESS/FAILED）
        vo.setProgressTotal(task.getProgressTotal());    // 总进度（表数量）
        vo.setProgressCurrent(task.getProgressCurrent()); // 当前进度（已处理表数量）
        vo.setStartedAt(task.getStartedAt());      // 开始时间
        vo.setFinishedAt(task.getFinishedAt());    // 结束时间
        vo.setErrorMessage(task.getErrorMessage()); // 错误信息（失败时）
        vo.setSnapshotId(task.getSnapshotId());
        return vo;
    }

    /**
     * 将元数据快照实体转换为视图对象。
     *
     * @param snapshot 元数据快照实体
     * @param dsNames  数据源ID到名称的映射，用于填充数据源名称
     * @return 快照视图对象
     */
    private SnapshotVO toSnapshotVO(MetadataSnapshot snapshot, Map<Long, String> dsNames) {
        SnapshotVO vo = new SnapshotVO();
        vo.setId(snapshot.getId());
        vo.setSnapshotVersion(snapshot.getSnapshotVersion());  // 快照版本号
        vo.setDatasourceId(snapshot.getDatasourceId());
        vo.setDatasourceName(dsNames.getOrDefault(snapshot.getDatasourceId(), "未知"));  // 从映射中获取数据源名称
        vo.setTableCount(snapshot.getTableCount());      // 表数量
        vo.setColumnCount(snapshot.getColumnCount());    // 列数量
        vo.setQualityScore(snapshot.getQualityScore());  // 质量评分
        vo.setStatus(snapshot.getStatus());              // 快照状态
        vo.setCreatedAt(snapshot.getCreatedAt());        // 创建时间
        return vo;
    }

    /**
     * 获取所有未删除数据源的ID到名称映射。
     * <p>
     * 使用 Stream 的 Collectors.toMap 将数据源列表转换为 Map 结构，
     * 用于快速查找数据源名称。
     * </p>
     *
     * @return 数据源ID到名称的映射
     */
    private Map<Long, String> getDatasourceNames() {
        return datasourceMapper.selectList(new LambdaQueryWrapper<Datasource>().eq(Datasource::getDeleted, 0L))
                .stream()
                .collect(Collectors.toMap(
                        Datasource::getId,      // key: 数据源ID
                        Datasource::getName));  // value: 数据源名称
    }
}
