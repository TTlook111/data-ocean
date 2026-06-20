package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.enums.VectorTaskStatus;
import com.dataocean.module.knowledge.mapper.VectorIndexTaskMapper;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 向量化任务管理业务实现类。
 * <p>
 * 实现 {@link VectorIndexTaskService} 接口，管理向量化任务的创建和状态流转。
 * 任务状态流转：PENDING → PROCESSING → COMPLETED/FAILED。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorIndexTaskServiceImpl implements VectorIndexTaskService {

    private final VectorIndexTaskMapper vectorIndexTaskMapper;

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public Long createTask(Long datasourceId, String targetType, Long targetId) {
        return createTask(datasourceId, targetType, targetId, null, null, null);
    }

    /**
     * 创建向量化任务（完整参数版本）。
     * <p>
     * 构建任务实体并插入数据库，初始状态为 PENDING。
     * 任务参数包括：
     * <ul>
     *   <li>datasourceId：数据源ID</li>
     *   <li>targetType：目标类型（DOCUMENT/QUERY 等）</li>
     *   <li>targetId：目标ID（文档ID等）</li>
     *   <li>metadataSnapshotId：元数据快照ID（可选）</li>
     *   <li>knowledgeVersionNo：知识库版本号（可选）</li>
     *   <li>previousVersionNo：前一版本号（可选，用于增量更新）</li>
     * </ul>
     * </p>
     *
     * @param datasourceId       数据源ID
     * @param targetType         目标类型
     * @param targetId           目标ID
     * @param metadataSnapshotId 元数据快照ID（可选）
     * @param knowledgeVersionNo 知识库版本号（可选）
     * @param previousVersionNo  前一版本号（可选）
     * @return 新创建的任务ID
     */
    @Transactional
    @Override
    public Long createTask(Long datasourceId,
                           String targetType,
                           Long targetId,
                           Long metadataSnapshotId,
                           Integer knowledgeVersionNo,
                           Integer previousVersionNo) {
        log.info("创建向量化任务 datasourceId={} targetType={} targetId={}", datasourceId, targetType, targetId);
        // 构建任务实体，初始状态为 PENDING
        VectorIndexTask task = VectorIndexTask.builder()
                .datasourceId(datasourceId)
                .targetType(targetType)
                .targetId(targetId)
                .metadataSnapshotId(metadataSnapshotId)       // 元数据快照ID，用于关联元数据版本
                .knowledgeVersionNo(knowledgeVersionNo)       // 知识库版本号，用于版本管理
                .previousVersionNo(previousVersionNo)         // 前一版本号，用于增量更新时清理旧向量
                .status(VectorTaskStatus.PENDING.name())      // 初始状态：PENDING（待处理）
                .build();
        vectorIndexTaskMapper.insert(task);
        log.info("向量化任务创建成功 taskId={}", task.getId());
        return task.getId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<VectorIndexTask> listPendingTasks() {
        // 查询所有 PENDING 状态的任务
        return vectorIndexTaskMapper.selectList(
                new LambdaQueryWrapper<VectorIndexTask>()
                        .eq(VectorIndexTask::getStatus, VectorTaskStatus.PENDING.name())
                        .orderByAsc(VectorIndexTask::getCreatedAt));
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void markProcessing(Long taskId) {
        VectorIndexTask task = requireTask(taskId);
        // 更新状态为处理中，记录开始时间
        task.setStatus(VectorTaskStatus.PROCESSING.name());
        task.setStartedAt(LocalDateTime.now());
        vectorIndexTaskMapper.updateById(task);
        log.info("向量化任务开始处理 taskId={}", taskId);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void markCompleted(Long taskId) {
        VectorIndexTask task = requireTask(taskId);
        // 更新状态为已完成，记录结束时间
        task.setStatus(VectorTaskStatus.COMPLETED.name());
        task.setFinishedAt(LocalDateTime.now());
        vectorIndexTaskMapper.updateById(task);
        log.info("向量化任务完成 taskId={}", taskId);
    }

    /**
     * {@inheritDoc}
     */
    @Transactional
    @Override
    public void markFailed(Long taskId, String errorMessage) {
        VectorIndexTask task = requireTask(taskId);
        // 更新状态为失败，记录结束时间和错误信息
        task.setStatus(VectorTaskStatus.FAILED.name());
        task.setFinishedAt(LocalDateTime.now());
        task.setErrorMessage(errorMessage);
        vectorIndexTaskMapper.updateById(task);
        log.warn("向量化任务失败 taskId={} errorMessage={}", taskId, errorMessage);
    }

    /**
     * 根据 ID 查询任务，不存在则抛出业务异常。
     *
     * @param taskId 任务 ID
     * @return 任务实体
     * @throws BusinessException 任务不存在时抛出
     */
    private VectorIndexTask requireTask(Long taskId) {
        VectorIndexTask task = vectorIndexTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("向量化任务不存在");
        }
        return task;
    }
}
