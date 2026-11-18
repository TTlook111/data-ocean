package com.dataocean.module.datasource.controller;

import com.dataocean.common.Ids;
import com.dataocean.common.PageVO;
import com.dataocean.common.result.Result;
import com.dataocean.module.datasource.entity.dto.SourceActionPreviewDTO;
import com.dataocean.module.datasource.entity.dto.StartCollectionDTO;
import com.dataocean.module.datasource.entity.query.CatalogQuery;
import com.dataocean.module.datasource.entity.query.CollectionQuery;
import com.dataocean.module.datasource.entity.vo.CatalogObjectDetailVO;
import com.dataocean.module.datasource.entity.vo.CatalogPageVO;
import com.dataocean.module.datasource.entity.vo.CollectionDetailVO;
import com.dataocean.module.datasource.service.impl.CollectionEventStream;
import com.dataocean.module.datasource.service.impl.CollectionServiceImpl;
import com.dataocean.module.datasource.service.impl.CredentialMaintenance;
import com.dataocean.module.datasource.service.impl.SourceManagement;
import com.dataocean.module.iam.entity.dto.VersionActionDTO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * 对外公开采集和目录读取，权限校验集中在相应业务服务。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CollectionController {

    /**
     * 持久任务、结构目录和取消核对。
     */
    private final CollectionServiceImpl collections;

    /**
     * 同一路径的配置、启用和计划动作。
     */
    private final SourceManagement management;

    /**
     * 使用当前活动任务的凭据替换预览。
     */
    private final CredentialMaintenance credentials;

    /**
     * 负责重核身份及发送已提交的任务进度。
     */
    private final CollectionEventStream streams;

    /**
     * 订阅同源采集状态，恢复连接时按当前许可读取最新任务。
     */
    @GetMapping(value = "/collection-tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String taskId, HttpServletRequest request) {
        return streams.open(taskId, request.getSession(false).getId());
    }

    /**
     * 从当前来源核对一次手动或重新采集请求。
     */
    @PostMapping("/datasources/{sourceId}/impact-preview")
    public Result<ImpactPreviewVO> review(
            @PathVariable String sourceId, @Valid @RequestBody SourceActionPreviewDTO input) {
        long id = Ids.parse(sourceId);
        return Result.success(
                switch (input.getAction()) {
                    case "START_COLLECTION" -> collections.review(id, input);
                    case "REPLACE_CREDENTIAL" -> credentials.review(id, input);
                    default -> management.review(id, input);
                });
    }

    /**
     * 接收来源采集，任务实际执行交给持久工作者。
     */
    @PostMapping("/datasources/{sourceId}/collections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<CollectionDetailVO> start(
            @PathVariable String sourceId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody StartCollectionDTO input) {
        return Result.success(collections.start(Ids.parse(sourceId), command, input));
    }

    /**
     * 分页读当前源的实际采集记录。
     */
    @GetMapping("/datasources/{sourceId}/collections")
    public Result<PageVO<CollectionDetailVO>> list(@PathVariable String sourceId, CollectionQuery query) {
        return Result.success(collections.list(Ids.parse(sourceId), query));
    }

    /**
     * 读取当前任务的可披露状态和证据。
     */
    @GetMapping("/collection-tasks/{taskId}")
    public Result<CollectionDetailVO> detail(@PathVariable String taskId) {
        return Result.success(collections.detail(taskId));
    }

    /**
     * 核对本次取消的原任务及影响。
     */
    @PostMapping("/collection-tasks/{taskId}/impact-preview")
    public Result<ImpactPreviewVO> reviewCancel(
            @PathVariable String taskId, @Valid @RequestBody SourceActionPreviewDTO input) {
        return Result.success(collections.reviewCancel(taskId, input));
    }

    /**
     * 记录取消意图。返回停止状态由真实源证据决定。
     */
    @PostMapping("/collection-tasks/{taskId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<CollectionDetailVO> cancel(
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String command,
            @Valid @RequestBody VersionActionDTO input) {
        return Result.success(collections.cancel(taskId, command, input));
    }

    /**
     * 读取当前配置范围内的成功目录及分页结果。
     */
    @GetMapping("/datasources/{sourceId}/catalog")
    public Result<CatalogPageVO> directory(@PathVariable String sourceId, CatalogQuery query) {
        return Result.success(collections.directory(Ids.parse(sourceId), query));
    }

    /**
     * 读取当前或指定的同源成功结构。
     */
    @GetMapping("/datasources/{sourceId}/objects/{objectId}")
    public Result<CatalogObjectDetailVO> object(
            @PathVariable String sourceId,
            @PathVariable String objectId,
            @RequestParam(required = false) String snapshotId) {
        return Result.success(
                collections.object(Ids.parse(sourceId), Ids.nullable(snapshotId), Ids.parse(objectId), false));
    }

    /**
     * 从成功快照读取尚未绑定稳定对象编号的实际观察。
     */
    @GetMapping("/datasources/{sourceId}/catalog-observations/{observationId}")
    public Result<CatalogObjectDetailVO> observation(
            @PathVariable String sourceId, @PathVariable String observationId) {
        return Result.success(collections.object(Ids.parse(sourceId), null, Ids.parse(observationId), true));
    }
}
