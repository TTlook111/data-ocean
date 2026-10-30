package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.fieldsemantic.service.FieldSemanticEvaluation;
import com.dataocean.module.fieldsemantic.service.FieldSemanticService;
import com.dataocean.module.knowledge.entity.po.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.po.RagIndexBuild;
import com.dataocean.module.knowledge.entity.po.RagIndexBuildChunk;
import com.dataocean.module.knowledge.entity.vo.FieldSemanticUsageVO;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.RagIndexBuildChunkMapper;
import com.dataocean.module.knowledge.service.FieldSemanticUsageService;
import com.dataocean.module.knowledge.service.RagIndexBuildService;
import com.dataocean.module.metadata.entity.po.DbColumnMeta;
import com.dataocean.module.metadata.mapper.DbColumnMetaMapper;
import com.dataocean.module.permission.s1.support.IamS1AdminGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Reads active build membership for status display; it never confirms or starts a RAG build. */
@Service
@RequiredArgsConstructor
public class FieldSemanticUsageServiceImpl implements FieldSemanticUsageService {
    private final DbColumnMetaMapper columnMapper;
    private final IamS1AdminGuard adminGuard;
    private final FieldSemanticService fieldSemanticService;
    private final RagIndexBuildService buildService;
    private final RagIndexBuildChunkMapper membershipMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;

    @Override
    public FieldSemanticUsageVO status(Long columnMetaId, Long snapshotId, Long userId) {
        DbColumnMeta column = columnMapper.selectById(columnMetaId);
        if (column == null || !Objects.equals(column.getSnapshotId(), snapshotId) || column.getDatasourceId() == null) {
            throw new BusinessException(404, "字段不属于请求指定的快照");
        }
        adminGuard.requireDatasourceFunction(userId, "governance:field:view", column.getDatasourceId());
        adminGuard.requireDatasourceFunction(userId, "knowledge:view", column.getDatasourceId());
        FieldSemanticEvaluation evaluation = fieldSemanticService.evaluate(columnMetaId, snapshotId);
        if (!evaluation.usable()) return status("UNAVAILABLE", evaluation.reason(), null, null, null, null, null);
        RagIndexBuild build = buildService.activeBuild(column.getDatasourceId());
        if (build == null) return status("NOT_BUILT", "字段解释已审核；该数据源尚无活动 RAG 构建。", null, null, null, null, null);
        if (!Objects.equals(build.getSourceSnapshotId(), snapshotId)) {
            return status("PENDING_REBUILD", "字段解释已审核；活动 RAG 构建绑定其他快照，需通过知识发布和单独确认构建收录当前版本。",
                    build.getBuildId(), build.getSourceSnapshotId(), null, null, null);
        }
        List<RagIndexBuildChunk> members = membershipMapper.selectList(new LambdaQueryWrapper<RagIndexBuildChunk>()
                .eq(RagIndexBuildChunk::getBuildId, build.getBuildId())
                .eq(RagIndexBuildChunk::getDatasourceId, column.getDatasourceId())
                .eq(RagIndexBuildChunk::getSourceSnapshotId, snapshotId)
                .eq(RagIndexBuildChunk::getFactType, "FIELD_SEMANTIC")
                .eq(RagIndexBuildChunk::getFactReviewStatus, "APPROVED"));
        List<String> expected = List.of(evaluation.definitionId().toString(), evaluation.revisionId().toString(),
                evaluation.reviewId().toString(), evaluation.bindingId().toString());
        for (RagIndexBuildChunk member : members) {
            try {
                List<String> ids = objectMapper.readValue(member.getFactSourceIds(), new TypeReference<>() {});
                if (!expected.equals(ids) || !fieldSemanticService.membershipValid(column.getDatasourceId(), snapshotId, ids)) continue;
                KnowledgeChunk chunk = chunkMapper.selectById(member.getChunkId());
                if (chunk == null || !"FIELD_SEMANTIC".equals(chunk.getFactType())
                        || !"APPROVED".equals(chunk.getReviewStatus())
                        || !"APPROVED".equals(chunk.getFactReviewStatus())
                        || !expected.equals(objectMapper.readValue(chunk.getFactSourceIds(), new TypeReference<>() {}))) continue;
                return status("INCLUDED", "当前已审核字段解释包含在活动 RAG 构建中。", build.getBuildId(),
                        snapshotId, chunk.getId(), chunk.getDocId(), chunk.getVersionNo());
            } catch (Exception ignored) {
                // Incomplete membership is not evidence of inclusion.
            }
        }
        return status("PENDING_UPDATE", "字段解释已审核；活动构建尚未收录当前精确修订，请更新知识文档并单独确认 RAG 构建。",
                build.getBuildId(), snapshotId, null, null, null);
    }

    private FieldSemanticUsageVO status(String status, String message, String buildId, Long snapshotId,
                                        Long chunkId, Long docId, Integer versionNo) {
        FieldSemanticUsageVO result = new FieldSemanticUsageVO();
        result.setStatus(status); result.setMessage(message); result.setBuildId(buildId);
        result.setSourceSnapshotId(snapshotId); result.setChunkId(chunkId); result.setDocId(docId);
        result.setVersionNo(versionNo); return result;
    }
}
