package com.dataocean.module.metadata.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.result.Result;
import com.dataocean.common.security.LoginUser;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

@RestController
@RequestMapping("/api/admin/metadata")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('metadata:manage')")
@Slf4j
public class MetadataCollectionController {

    private final SchemaCollectionService collectionService;
    private final SchemaDiffService diffService;
    private final SchemaSyncTaskMapper syncTaskMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final DbTableMetaMapper tableMetaMapper;
    private final DbColumnMetaMapper columnMetaMapper;
    private final DatasourceMapper datasourceMapper;

    @PostMapping("/sync")
    public Result<Map<String, Long>> triggerSync(@Valid @RequestBody SyncTriggerDTO request,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        Long taskId = collectionService.executeFullSync(request.getDatasourceId(), loginUser.getUserId(), request.getIncludeStatistics());
        return Result.success("同步任务已触发", Map.of("taskId", taskId));
    }

    @GetMapping("/sync-tasks")
    public Result<Page<SyncTaskVO>> listSyncTasks(@RequestParam(required = false) Long datasourceId,
                                                  @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "20") Integer size) {
        Page<SchemaSyncTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<SchemaSyncTask> wrapper = new LambdaQueryWrapper<SchemaSyncTask>()
                .eq(datasourceId != null, SchemaSyncTask::getDatasourceId, datasourceId)
                .orderByDesc(SchemaSyncTask::getCreatedAt);
        Page<SchemaSyncTask> result = syncTaskMapper.selectPage(pageParam, wrapper);

        Map<Long, String> dsNames = getDatasourceNames();
        Page<SyncTaskVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(t -> toSyncTaskVO(t, dsNames)).toList());
        return Result.success(voPage);
    }

    @GetMapping("/snapshots")
    public Result<Page<SnapshotVO>> listSnapshots(@RequestParam(required = false) Long datasourceId,
                                                  @RequestParam(defaultValue = "1") Integer page,
                                                  @RequestParam(defaultValue = "20") Integer size) {
        Page<MetadataSnapshot> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<MetadataSnapshot> wrapper = new LambdaQueryWrapper<MetadataSnapshot>()
                .eq(datasourceId != null, MetadataSnapshot::getDatasourceId, datasourceId)
                .orderByDesc(MetadataSnapshot::getCreatedAt);
        Page<MetadataSnapshot> result = snapshotMapper.selectPage(pageParam, wrapper);

        Map<Long, String> dsNames = getDatasourceNames();
        Page<SnapshotVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(s -> toSnapshotVO(s, dsNames)).toList());
        return Result.success(voPage);
    }

    @GetMapping("/snapshots/{id}")
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
        detail.put("tables", tables);
        detail.put("columns", columns);
        return Result.success(detail);
    }

    @GetMapping("/snapshots/diff")
    public Result<SchemaDiffVO> diffSnapshots(@RequestParam Long oldId, @RequestParam Long newId) {
        return Result.success(diffService.compareSnapshots(oldId, newId));
    }

    private SyncTaskVO toSyncTaskVO(SchemaSyncTask task, Map<Long, String> dsNames) {
        SyncTaskVO vo = new SyncTaskVO();
        vo.setId(task.getId());
        vo.setDatasourceName(dsNames.getOrDefault(task.getDatasourceId(), "未知"));
        vo.setTriggerType(task.getTriggerType());
        vo.setStatus(task.getStatus());
        vo.setProgressTotal(task.getProgressTotal());
        vo.setProgressCurrent(task.getProgressCurrent());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setErrorMessage(task.getErrorMessage());
        return vo;
    }

    private SnapshotVO toSnapshotVO(MetadataSnapshot snapshot, Map<Long, String> dsNames) {
        SnapshotVO vo = new SnapshotVO();
        vo.setId(snapshot.getId());
        vo.setSnapshotVersion(snapshot.getSnapshotVersion());
        vo.setDatasourceName(dsNames.getOrDefault(snapshot.getDatasourceId(), "未知"));
        vo.setTableCount(snapshot.getTableCount());
        vo.setColumnCount(snapshot.getColumnCount());
        vo.setQualityScore(snapshot.getQualityScore());
        vo.setStatus(snapshot.getStatus());
        vo.setCreatedAt(snapshot.getCreatedAt());
        return vo;
    }

    private Map<Long, String> getDatasourceNames() {
        return datasourceMapper.selectList(new LambdaQueryWrapper<Datasource>().eq(Datasource::getDeleted, 0L))
                .stream().collect(Collectors.toMap(Datasource::getId, Datasource::getName));
    }
}
