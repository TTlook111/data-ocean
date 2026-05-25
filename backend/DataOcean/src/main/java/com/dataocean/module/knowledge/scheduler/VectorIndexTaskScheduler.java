package com.dataocean.module.knowledge.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.VectorIndexTask;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 向量化任务定时调度器
 * <p>
 * 每 5 分钟扫描 PENDING 状态的向量化任务，
 * 调用 007 模块的向量化接口处理。
 * </p>
 *
 * @author dataocean
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorIndexTaskScheduler {

    private final VectorIndexTaskService vectorIndexTaskService;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final PythonRagClient pythonRagClient;

    /**
     * 定时扫描并处理待向量化任务
     * <p>
     * 每 5 分钟执行一次，查询所有 PENDING 状态的任务，
     * 逐个标记为 PROCESSING 后调用向量化接口。
     * </p>
     */
    @Scheduled(fixedDelay = 300000)
    public void processVectorTasks() {
        // 查询所有待处理的向量化任务
        List<VectorIndexTask> pendingTasks = vectorIndexTaskService.listPendingTasks();
        if (pendingTasks.isEmpty()) {
            return;
        }
        log.info("扫描到待处理向量化任务 count={}", pendingTasks.size());
        for (VectorIndexTask task : pendingTasks) {
            try {
                // 标记为处理中
                vectorIndexTaskService.markProcessing(task.getId());
                processTask(task);
                vectorIndexTaskService.markCompleted(task.getId());
                log.info("向量化任务处理完成 taskId={} targetType={} targetId={}",
                        task.getId(), task.getTargetType(), task.getTargetId());
            } catch (Exception e) {
                // 处理失败，记录错误信息
                log.error("向量化任务处理失败 taskId={}", task.getId(), e);
                vectorIndexTaskService.markFailed(task.getId(), e.getMessage());
            }
        }
    }

    /**
     * 处理单个向量化任务：查询切片、调用 Python RAG 服务、更新切片状态。
     *
     * @param task 向量化任务实体
     * @throws BusinessException 任务类型不支持、缺少上下文、切片为空或向量化未完成时抛出
     */
    private void processTask(VectorIndexTask task) {
        if (!"DOC".equals(task.getTargetType()) && !"KNOWLEDGE_DOC".equals(task.getTargetType())) {
            throw new BusinessException("暂不支持的向量化目标类型：" + task.getTargetType());
        }
        if (task.getMetadataSnapshotId() == null || task.getKnowledgeVersionNo() == null) {
            throw new BusinessException("向量化任务缺少快照或版本上下文，请重新发布 skills.md");
        }

        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, task.getTargetId())
                        .eq(KnowledgeChunk::getVersionNo, task.getKnowledgeVersionNo())
                        .eq(KnowledgeChunk::getReviewStatus, ReviewStatus.APPROVED.name())
                        .orderByAsc(KnowledgeChunk::getId));
        if (chunks.isEmpty()) {
            throw new BusinessException("未找到可向量化的已审核知识切片");
        }

        // 同版本号意味着重新发布同一版本（如内容未变但需要重建向量），需先清理再写入
        boolean forceRebuild = Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo());
        Map<String, Object> response = pythonRagClient.vectorize(task, chunks, forceRebuild);
        String status = String.valueOf(response.getOrDefault("status", ""));
        int vectorizedCount = toInt(firstPresent(response, "vectorizedCount", "successCount", "success_count"));
        if (!"COMPLETED".equals(status) || vectorizedCount != chunks.size()) {
            throw new BusinessException("RAG 向量化未完成，status=" + status + " vectorizedCount=" + vectorizedCount);
        }

        knowledgeChunkMapper.update(null,
                new LambdaUpdateWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, task.getTargetId())
                        .eq(KnowledgeChunk::getVersionNo, task.getKnowledgeVersionNo())
                        .set(KnowledgeChunk::getVectorStatus, "INDEXED"));
        if (task.getPreviousVersionNo() != null
                && !Objects.equals(task.getPreviousVersionNo(), task.getKnowledgeVersionNo())) {
            knowledgeChunkMapper.update(null,
                    new LambdaUpdateWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getDocId, task.getTargetId())
                            .eq(KnowledgeChunk::getVersionNo, task.getPreviousVersionNo())
                            .set(KnowledgeChunk::getVectorStatus, "SUPERSEDED"));
        }
    }

    /**
     * 从响应 Map 中按优先级查找第一个存在的键对应的值。
     *
     * @param response Python 服务响应
     * @param keys     按优先级排列的候选键名
     * @return 第一个匹配的值，全部不存在则返回 null
     */
    private Object firstPresent(Map<String, Object> response, String... keys) {
        for (String key : keys) {
            if (response.containsKey(key)) {
                return response.get(key);
            }
        }
        return null;
    }

    /**
     * 将任意类型的值安全转换为 int。
     * <p>
     * 支持 Number 和 String 类型，解析失败时返回 0 并记录警告日志。
     * </p>
     *
     * @param value 待转换的值
     * @return 转换后的整数值，无法转换时返回 0
     */
    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                log.warn("Python 返回的向量化数量无法解析为整数: {}", text);
                return 0;
            }
        }
        return 0;
    }
}
