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
        log.info("创建向量化任务 datasourceId={} targetType={} targetId={}", datasourceId, targetType, targetId);
        // 构建任务实体，初始状态为 PENDING
        VectorIndexTask task = VectorIndexTask.builder()
                .datasourceId(datasourceId)
                .targetType(targetType)
                .targetId(targetId)
                .status(VectorTaskStatus.PENDING.name())
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
