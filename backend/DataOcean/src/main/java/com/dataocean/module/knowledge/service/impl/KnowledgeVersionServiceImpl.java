package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.enums.GenerationSource;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.knowledge.service.VectorIndexTaskService;
import com.dataocean.module.knowledge.support.KnowledgeChunkSplitter;
import com.dataocean.module.knowledge.support.KnowledgeDependencySnapshotBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 知识文档版本管理业务实现类。
 * <p>
 * 实现 {@link KnowledgeVersionService} 接口，提供版本的创建、查询和回滚功能。
 * 每次创建新版本时自动递增版本号，并同步更新文档主表的 currentVersion 和 content。
 * </p>
 *
 * @author DataOcean
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeVersionServiceImpl implements KnowledgeVersionService {

    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final VectorIndexTaskService vectorIndexTaskService;
    private final KnowledgeDependencySnapshotBuilder dependencySnapshotBuilder;
    private final KnowledgeChunkSplitter knowledgeChunkSplitter;

    /**
     * {@inheritDoc}
     */
    @Override
    public List<KnowledgeDocVersion> listVersions(Long docId) {
        // 按文档 ID 查询所有版本，按版本号降序排列
        return knowledgeDocVersionMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .orderByDesc(KnowledgeDocVersion::getVersionNo));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public KnowledgeDocVersion getVersion(Long docId, Integer versionNo) {
        // 按文档 ID 和版本号查询唯一版本记录
        KnowledgeDocVersion version = knowledgeDocVersionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeDocVersion>()
                        .eq(KnowledgeDocVersion::getDocId, docId)
                        .eq(KnowledgeDocVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new BusinessException("版本不存在");
        }
        return version;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 查询当前最大版本号并 +1
     * 2. 插入新版本记录
     * 3. 同步更新文档主表的 currentVersion 和 content
     * </p>
     */
    @Transactional
    @Override
    public Integer createVersion(Long docId, String content, String generationSource, Long snapshotId, String changeSummary) {
        // 查询文档，校验存在性
        KnowledgeDoc doc = knowledgeDocMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        // 查询当前最大版本号
        Integer maxVersionNo = doc.getCurrentVersion();
        int newVersionNo = (maxVersionNo == null ? 0 : maxVersionNo) + 1;

        // 构建版本实体
        KnowledgeDocVersion version = KnowledgeDocVersion.builder()
                .docId(docId)
                .datasourceId(doc.getDatasourceId())
                .metadataSnapshotId(snapshotId)
                .dependencySnapshot(dependencySnapshotBuilder.build(
                        doc.getDatasourceId(),
                        snapshotId,
                        generationSource))
                .versionNo(newVersionNo)
                .content(content)
                .generationSource(generationSource)
                .changeSummary(changeSummary)
                .createdBy(UserContext.currentUserId())
                .build();
        knowledgeDocVersionMapper.insert(version);

        // 同步更新文档主表的当前版本号和内容
        doc.setCurrentVersion(newVersionNo);
        doc.setContent(content);
        doc.setUpdatedBy(UserContext.currentUserId());
        knowledgeDocMapper.updateById(doc);

        log.info("创建文档版本成功 docId={} versionNo={} source={}", docId, newVersionNo, generationSource);
        return newVersionNo;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：
     * 1. 查询目标版本内容
     * 2. 以 ROLLBACK 来源创建新版本
     * 3. 创建向量化任务更新 RAG 索引
     * </p>
     */
    @Transactional
    @Override
    public Integer rollback(Long docId, Integer targetVersionNo) {
        log.info("开始回滚文档版本 docId={} targetVersionNo={}", docId, targetVersionNo);
        // 查询目标版本
        KnowledgeDocVersion targetVersion = getVersion(docId, targetVersionNo);
        KnowledgeDoc docBeforeRollback = knowledgeDocMapper.selectById(docId);
        Integer previousVersionNo = docBeforeRollback == null ? null : docBeforeRollback.getCurrentVersion();

        // 以 ROLLBACK 来源创建新版本
        Integer newVersionNo = createVersion(
                docId,
                targetVersion.getContent(),
                GenerationSource.ROLLBACK.name(),
                targetVersion.getMetadataSnapshotId(),
                "回滚到版本 " + targetVersionNo);

        // 查询文档获取数据源 ID，创建向量化任务
        KnowledgeDoc doc = knowledgeDocMapper.selectById(docId);
        knowledgeChunkSplitter.splitAndSave(
                doc.getId(), doc.getCurrentVersion(),
                targetVersion.getMetadataSnapshotId(), doc.getContent());
        vectorIndexTaskService.createTask(
                doc.getDatasourceId(),
                "DOC",
                docId,
                targetVersion.getMetadataSnapshotId(),
                newVersionNo,
                previousVersionNo);

        log.info("文档版本回滚成功 docId={} fromVersion={} newVersion={}", docId, targetVersionNo, newVersionNo);
        return newVersionNo;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现逻辑：逐行对比两个版本的内容，标记新增、删除和相同的行。
     * 使用简单的最长公共子序列（LCS）算法实现行级 diff。
     * </p>
     */
    @Override
    public List<Map<String, Object>> diffVersions(Long docId, Integer v1, Integer v2) {
        // 获取两个版本的内容
        KnowledgeDocVersion version1 = getVersion(docId, v1);
        KnowledgeDocVersion version2 = getVersion(docId, v2);

        String[] lines1 = (version1.getContent() != null ? version1.getContent() : "").split("\n", -1);
        String[] lines2 = (version2.getContent() != null ? version2.getContent() : "").split("\n", -1);

        // 使用 LCS 算法计算行级差异
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        int[][] lcs = buildLcsTable(lines1, lines2);
        buildDiff(lines1, lines2, lcs, lines1.length, lines2.length, result);

        return result;
    }

    /**
     * 构建 LCS（最长公共子序列）表。
     *
     * @param lines1 版本 1 的行数组
     * @param lines2 版本 2 的行数组
     * @return LCS 动态规划表
     */
    private int[][] buildLcsTable(String[] lines1, String[] lines2) {
        int m = lines1.length;
        int n = lines2.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (lines1[i - 1].equals(lines2[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    /**
     * 根据 LCS 表回溯生成 diff 结果。
     *
     * @param lines1 版本 1 的行数组
     * @param lines2 版本 2 的行数组
     * @param lcs    LCS 动态规划表
     * @param i      当前行索引（版本 1）
     * @param j      当前行索引（版本 2）
     * @param result diff 结果列表
     */
    private void buildDiff(String[] lines1, String[] lines2, int[][] lcs, int i, int j, List<Map<String, Object>> result) {
        if (i > 0 && j > 0 && lines1[i - 1].equals(lines2[j - 1])) {
            buildDiff(lines1, lines2, lcs, i - 1, j - 1, result);
            result.add(Map.of("type", "EQUAL", "content", lines1[i - 1]));
        } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
            buildDiff(lines1, lines2, lcs, i, j - 1, result);
            result.add(Map.of("type", "ADD", "content", lines2[j - 1]));
        } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
            buildDiff(lines1, lines2, lcs, i - 1, j, result);
            result.add(Map.of("type", "DELETE", "content", lines1[i - 1]));
        }
    }
}
