package com.dataocean.module.datasource.service;

import com.aliyun.sdk.service.oss2.exceptions.OperationException;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.module.datasource.entity.po.NoteAttachmentPO;
import com.dataocean.module.datasource.mapper.NoteAttachmentCleanupMapper;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 按持久化预留回收未提交的上传，并在资料删除后清理对应原件。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NoteAttachmentCleanup {

    /**
     * 任务状态及同一对象的排他锁。
     */
    private final NoteAttachmentCleanupMapper tasks;

    /**
     * 当前环境的对象删除能力。
     */
    private final NoteAttachmentStorage storage;

    /**
     * 先于资料写事务登记新对象，进程中断也能找到未提交的文件。
     */
    @Transactional
    public void reserveUploads(List<NoteAttachmentPO> files) {
        for (var file : files) {
            // 一个预留只绑定一个新对象和实际目标 Bucket。
            tasks.reserveUpload(file.getObjectBucket(), file.getObjectKey());
        }
    }

    /**
     * 资料写事务获得预留行锁后才能上传原件。
     */
    public void lockUpload(NoteAttachmentPO file) {
        if (tasks.lockUpload(file.getObjectBucket(), file.getObjectKey()) == null) {
            // 已被清理的预留不允许再写入，重试会分配新对象键。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_UNAVAILABLE, "附件上传等待超时，请重新提交");
        }
    }

    /**
     * 资料写事务提交时一并撤销预留，回滚则保留清理依据。
     */
    public void completeUploads(List<NoteAttachmentPO> files) {
        for (var file : files) {
            // 防重命令重放没有执行新上传，同样可移除本次的空预留。
            tasks.complete(file.getObjectBucket(), file.getObjectKey());
        }
    }

    /**
     * 与资料删除同事务登记原件清理，以提交结果决定是否对外删除。
     */
    public void queueDeletion(long note) {
        tasks.queueNote(note);
    }

    /**
     * 每轮有界地处理一个对象，保留失败任务供后续调度重试。
     */
    @Scheduled(fixedDelay = 1000, scheduler = "noteAttachmentCleanupScheduler")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cleanNext() {
        var bucket = storage.configuredBucket();
        if (bucket == null) {
            // 配置尚未提供时保留任务，不执行远端清理。
            return;
        }
        var objectKey = tasks.next(bucket);
        if (objectKey == null) {
            // 当前没有到期对象，等待下一轮调度。
            return;
        }
        try {
            // 行锁跨过远端删除，避免同时处理或与正在上传的对象竞争。
            storage.delete(bucket, objectKey);
        } catch (OperationException | ServiceException | BizIllegalException failure) {
            // 不记录签名请求与报文，保留可关联的对象标识和重试状态。
            tasks.retry(bucket, objectKey);
            log.warn(
                    "附件 OSS 删除等待重试，objectKey={}, errorType={}",
                    objectKey,
                    failure.getClass().getSimpleName());
            return;
        }
        tasks.complete(bucket, objectKey);
    }
}
