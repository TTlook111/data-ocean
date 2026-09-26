package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dataocean.common.exception.BusinessException;
import com.dataocean.module.knowledge.client.PythonRagClient;
import com.dataocean.module.knowledge.entity.KnowledgeChunk;
import com.dataocean.module.knowledge.entity.KnowledgeDoc;
import com.dataocean.module.knowledge.entity.KnowledgeDocVersion;
import com.dataocean.module.knowledge.entity.RagIndexBuild;
import com.dataocean.module.knowledge.entity.RagIndexBuildChunk;
import com.dataocean.module.knowledge.entity.RagIndexState;
import com.dataocean.module.knowledge.enums.DocStatus;
import com.dataocean.module.knowledge.enums.ReviewStatus;
import com.dataocean.module.knowledge.mapper.KnowledgeChunkMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocVersionMapper;
import com.dataocean.module.knowledge.mapper.RagIndexBuildChunkMapper;
import com.dataocean.module.knowledge.mapper.RagIndexBuildMapper;
import com.dataocean.module.knowledge.mapper.RagIndexStateMapper;
import com.dataocean.module.knowledge.service.RagIndexBuildService;
import com.dataocean.module.knowledge.support.KnowledgeSnapshotFactValidator;
import com.dataocean.module.metadata.entity.MetadataSnapshot;
import com.dataocean.module.metadata.mapper.MetadataSnapshotMapper;
import com.dataocean.module.query.entity.QueryTask;
import com.dataocean.module.query.mapper.QueryTaskMapper;
import com.dataocean.module.system.entity.vo.AiConfigVO;
import com.dataocean.module.system.service.AiConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Owns build confirmation, verification, atomic pointer switching, and old-index cleanup. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagIndexBuildServiceImpl implements RagIndexBuildService {
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_BUILDING = "BUILDING";
    private static final String STATUS_VERIFIED = "VERIFIED";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CLEANUP_PENDING = "CLEANUP_PENDING";
    private static final String STATUS_FAILED_CLEANUP_PENDING = "FAILED_CLEANUP_PENDING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CLEANED = "CLEANED";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";
    private static final List<String> INDEXABLE_GOVERNANCE =
            List.of("NORMAL", "RECOMMENDED", "SENSITIVE");

    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeDocVersionMapper knowledgeDocVersionMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final RagIndexBuildMapper buildMapper;
    private final RagIndexStateMapper stateMapper;
    private final RagIndexBuildChunkMapper buildChunkMapper;
    private final MetadataSnapshotMapper snapshotMapper;
    private final PythonRagClient pythonRagClient;
    private final AiConfigService aiConfigService;
    private final KnowledgeSnapshotFactValidator snapshotFactValidator;
    private final QueryTaskMapper queryTaskMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    public RagIndexBuild confirmBuild(Long datasourceId, Long snapshotId, Long userId, boolean confirmed) {
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new BusinessException("必须在页面明确确认本次 RAG 构建");
        }
        if (datasourceId == null || snapshotId == null || userId == null) {
            throw new BusinessException("RAG 构建缺少数据源、快照或确认人");
        }
        MetadataSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null || !datasourceId.equals(snapshot.getDatasourceId())
                || !(MetadataSnapshot.STATUS_PUBLISHED.equals(snapshot.getStatus())
                || MetadataSnapshot.STATUS_EXPIRED.equals(snapshot.getStatus()))) {
            throw new BusinessException("RAG 只能从属于该数据源且曾经发布的元数据快照构建");
        }

        List<KnowledgeDocVersion> versions = collectPublishedSnapshotVersions(datasourceId, snapshotId);
        if (versions.isEmpty()) {
            throw new BusinessException("没有已发布且绑定到该快照的审核通过文档可构建");
        }
        AiConfigVO.EmbeddingConfig targetEmbedding = targetEmbeddingConfig();
        String fingerprint = embeddingFingerprint(targetEmbedding);
        String buildId = UUID.randomUUID().toString();
        String collection = collectionName(datasourceId, buildId);
        RagIndexState state = stateMapper.selectForUpdate(datasourceId);
        if (state == null) {
            state = new RagIndexState();
            state.setDatasourceId(datasourceId);
            state.setNextGeneration(1L);
            state.setActiveBuildId(null);
        }
        long generation = state.getNextGeneration() == null ? 1L : state.getNextGeneration();
        state.setLatestBuildId(buildId);
        state.setNextGeneration(generation + 1);
        if (stateMapper.selectById(datasourceId) == null) stateMapper.insert(state);
        else stateMapper.updateById(state);

        Map<String, Object> manifest = buildManifest(datasourceId, snapshotId, versions);
        RagIndexBuild build = RagIndexBuild.builder()
                .buildId(buildId)
                .datasourceId(datasourceId)
                .sourceSnapshotId(snapshotId)
                .collectionName(collection)
                .embeddingProviderId(targetEmbedding.getProviderId())
                .embeddingModel(targetEmbedding.getModel())
                .embeddingBaseUrl(providerBaseUrl(targetEmbedding.getProviderId()))
                .embeddingDimension(targetEmbedding.getDimension())
                .embeddingIndexVersion(targetEmbedding.getIndexVersion())
                .embeddingFingerprint(fingerprint)
                .buildGeneration(generation)
                .manifestJson(writeJson(manifest))
                .status(STATUS_QUEUED)
                .expectedChunkCount(0)
                .actualVectorCount(0)
                .confirmationUserId(userId)
                .confirmedAt(LocalDateTime.now())
                .build();
        buildMapper.insert(build);

        log.info("RAG build 已明确确认 buildId={} datasourceId={} snapshotId={} generation={} chunks={} userId={}",
                buildId, datasourceId, snapshotId, generation, 0, userId);
        return build;
    }

    @Override
    public List<RagIndexBuild> listForDatasource(Long datasourceId) {
        return buildMapper.selectList(new LambdaQueryWrapper<RagIndexBuild>()
                .eq(RagIndexBuild::getDatasourceId, datasourceId)
                .orderByDesc(RagIndexBuild::getBuildGeneration));
    }

    @Override
    public RagIndexBuild activeBuild(Long datasourceId) {
        RagIndexState state = stateMapper.selectById(datasourceId);
        return activeBuildFromState(state);
    }

    @Override
    public RagIndexBuild activeBuildForQuery(Long datasourceId) {
        // QueryService is @Transactional: this row lock prevents cleanup from racing
        // between selecting the build and inserting its durable query_task record.
        RagIndexState state = stateMapper.selectForUpdate(datasourceId);
        return activeBuildFromState(state);
    }

    @Override
    public RagIndexBuild buildForQuery(String buildId, Long datasourceId) {
        if (buildId == null || datasourceId == null) return null;
        RagIndexBuild build = buildMapper.selectById(buildId);
        if (build == null || !datasourceId.equals(build.getDatasourceId())
                || STATUS_CLEANED.equals(build.getStatus()) || STATUS_FAILED.equals(build.getStatus())) return null;
        return build;
    }

    private RagIndexBuild activeBuildFromState(RagIndexState state) {
        if (state == null || state.getActiveBuildId() == null) return null;
        RagIndexBuild build = buildMapper.selectById(state.getActiveBuildId());
        return build != null && STATUS_ACTIVE.equals(build.getStatus()) ? build : null;
    }

    @Override
    public Map<String, Object> embeddingConfigForQuery(RagIndexBuild build) {
        if (build == null) return Map.of();
        return executionEmbeddingConfig(build);
    }

    @Override
    public void processQueuedBuilds() {
        List<RagIndexBuild> queued = buildMapper.selectList(new LambdaQueryWrapper<RagIndexBuild>()
                .eq(RagIndexBuild::getStatus, STATUS_QUEUED)
                .orderByAsc(RagIndexBuild::getConfirmedAt)
                .last("LIMIT 2"));
        for (RagIndexBuild build : queued) {
            processOne(build.getBuildId());
        }
    }

    private void processOne(String buildId) {
        int claimed = buildMapper.update(null, new LambdaUpdateWrapper<RagIndexBuild>()
                .eq(RagIndexBuild::getBuildId, buildId)
                .eq(RagIndexBuild::getStatus, STATUS_QUEUED)
                .set(RagIndexBuild::getStatus, STATUS_BUILDING)
                .set(RagIndexBuild::getStartedAt, LocalDateTime.now()));
        if (claimed == 0) return;
        RagIndexBuild build = buildMapper.selectById(buildId);
        try {
            Map<String, Object> executionConfig = executionEmbeddingConfig(build);
            Map<String, List<KnowledgeChunk>> byDocumentVersion = chunkAndStoreBuildFacts(build);
            int expectedChunkCount = byDocumentVersion.values().stream().mapToInt(List::size).sum();
            if (expectedChunkCount == 0) {
                throw new IllegalStateException("本次知识文档切分后没有已审核且可检索的事实 chunk");
            }
            build.setExpectedChunkCount(expectedChunkCount);
            buildMapper.updateById(build);
            int vectorized = 0;
            for (List<KnowledgeChunk> chunks : byDocumentVersion.values()) {
                assertBuildStillTargeted(build);
                Long docId = chunks.get(0).getDocId();
                Integer versionNo = chunks.get(0).getVersionNo();
                Map<String, Object> response = pythonRagClient.vectorizeBuild(
                        build, docId, versionNo, chunks, executionConfig);
                String status = String.valueOf(response.getOrDefault("status", ""));
                int successCount = number(response.get("vectorizedCount"));
                if (!"COMPLETED".equals(status) || successCount != chunks.size()) {
                    throw new IllegalStateException("Milvus vectorize 返回数量不匹配 expected="
                            + chunks.size() + " actual=" + successCount + " status=" + status);
                }
                vectorized += successCount;
                build.setActualVectorCount(vectorized);
                buildMapper.updateById(build);
            }
            if (vectorized != build.getExpectedChunkCount()) {
                throw new IllegalStateException("Milvus 向量数量与冻结 manifest 不一致");
            }
            Map<String, Object> count = pythonRagClient.countBuildCollection(buildId, build.getCollectionName());
            if (!Boolean.TRUE.equals(count.get("verified"))
                    || number(count.get("vectorCount")) != build.getExpectedChunkCount()) {
                throw new IllegalStateException("Milvus 构建集合数量验证失败");
            }
            build.setActualVectorCount(number(count.get("vectorCount")));
            build.setStatus(STATUS_VERIFIED);
            build.setVerifiedAt(LocalDateTime.now());
            buildMapper.updateById(build);
            activateVerifiedBuild(build);
        } catch (Exception e) {
            log.error("RAG build failed buildId={} datasourceId={}", buildId, build.getDatasourceId(), e);
            failAndClean(build, e);
        }
    }

    private void activateVerifiedBuild(RagIndexBuild build) {
        transactionTemplate.executeWithoutResult(status -> activateVerifiedBuildInTransaction(build));
    }

    private void activateVerifiedBuildInTransaction(RagIndexBuild build) {
        RagIndexState state = stateMapper.selectForUpdate(build.getDatasourceId());
        if (state == null || !build.getBuildId().equals(state.getLatestBuildId())) {
            build.setStatus(STATUS_FAILED_CLEANUP_PENDING);
            build.setErrorMessage("有更新的构建代际，拒绝迟到构建切换");
            buildMapper.updateById(build);
            return;
        }
        if (!build.getEmbeddingFingerprint().equals(currentTargetFingerprint())) {
            build.setStatus(STATUS_FAILED_CLEANUP_PENDING);
            build.setErrorMessage("构建期间 Embedding 目标已变化，拒绝迟到配置切换");
            buildMapper.updateById(build);
            return;
        }
        String oldBuildId = state.getActiveBuildId();
        state.setActiveBuildId(build.getBuildId());
        stateMapper.updateById(state);
        build.setStatus(STATUS_ACTIVE);
        build.setActivatedAt(LocalDateTime.now());
        buildMapper.updateById(build);
        if (oldBuildId != null && !oldBuildId.equals(build.getBuildId())) {
            RagIndexBuild old = buildMapper.selectById(oldBuildId);
            if (old != null) {
                old.setStatus(STATUS_CLEANUP_PENDING);
                old.setSupersededAt(LocalDateTime.now());
                old.setCleanupAfter(LocalDateTime.now());
                buildMapper.updateById(old);
            }
        }
        log.info("RAG build pointer switched datasourceId={} oldBuildId={} buildId={} sourceSnapshotId={}",
                build.getDatasourceId(), oldBuildId, build.getBuildId(), build.getSourceSnapshotId());
    }

    private void assertBuildStillTargeted(RagIndexBuild build) {
        RagIndexState state = stateMapper.selectById(build.getDatasourceId());
        if (state == null || !build.getBuildId().equals(state.getLatestBuildId())) {
            throw new IllegalStateException("RAG build 已被更新的确认任务取代");
        }
        if (!build.getEmbeddingFingerprint().equals(currentTargetFingerprint())) {
            throw new IllegalStateException("Embedding 目标已变化，停止旧 build");
        }
    }

    @Override
    public void cleanupSupersededBuilds() {
        List<RagIndexBuild> pending = buildMapper.selectList(new LambdaQueryWrapper<RagIndexBuild>()
                .in(RagIndexBuild::getStatus, STATUS_CLEANUP_PENDING, STATUS_FAILED_CLEANUP_PENDING)
                .orderByAsc(RagIndexBuild::getSupersededAt)
                .last("LIMIT 20"));
        for (RagIndexBuild build : pending) cleanupOne(build);
    }

    private void cleanupOne(RagIndexBuild build) {
        RagIndexState state = stateMapper.selectById(build.getDatasourceId());
        if (state != null && build.getBuildId().equals(state.getActiveBuildId())) return;
        Long inFlight = queryTaskMapper.selectCount(new LambdaQueryWrapper<QueryTask>()
                .eq(QueryTask::getDatasourceId, build.getDatasourceId())
                .eq(QueryTask::getRagBuildId, build.getBuildId())
                .eq(QueryTask::getStatus, "PROCESSING"));
        if (inFlight != null && inFlight > 0) return;
        try {
            Map<String, Object> result = pythonRagClient.deleteBuildCollection(build.getBuildId(), build.getCollectionName());
            if (!Boolean.TRUE.equals(result.get("verified"))
                    || number(result.get("vectorCount")) != 0
                    || Boolean.TRUE.equals(result.get("collectionExists"))) {
                throw new IllegalStateException("Milvus 旧 build 清理后零残留验证失败");
            }
            build.setCleanupVerifiedAt(LocalDateTime.now());
            build.setStatus(STATUS_FAILED_CLEANUP_PENDING.equals(build.getStatus()) ? STATUS_FAILED : STATUS_CLEANED);
            buildMapper.updateById(build);
        } catch (Exception e) {
            build.setStatus(build.getStatus() == null ? STATUS_CLEANUP_PENDING : build.getStatus());
            build.setErrorMessage(safeMessage(e));
            buildMapper.updateById(build);
            log.warn("RAG build collection cleanup will retry buildId={} collection={}",
                    build.getBuildId(), build.getCollectionName(), e);
        }
    }

    private void failAndClean(RagIndexBuild build, Exception cause) {
        build.setErrorMessage(safeMessage(cause));
        try {
            Map<String, Object> result = pythonRagClient.deleteBuildCollection(build.getBuildId(), build.getCollectionName());
            if (Boolean.TRUE.equals(result.get("verified"))
                    && number(result.get("vectorCount")) == 0
                    && !Boolean.TRUE.equals(result.get("collectionExists"))) {
                build.setStatus(STATUS_FAILED);
            } else {
                build.setStatus(STATUS_FAILED_CLEANUP_PENDING);
            }
        } catch (Exception cleanupError) {
            build.setStatus(STATUS_FAILED_CLEANUP_PENDING);
            build.setErrorMessage(build.getErrorMessage() + "; cleanup: " + safeMessage(cleanupError));
        }
        buildMapper.updateById(build);
    }

    private List<KnowledgeDocVersion> collectPublishedSnapshotVersions(Long datasourceId, Long snapshotId) {
        List<KnowledgeDoc> docs = knowledgeDocMapper.selectList(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getDatasourceId, datasourceId)
                .eq(KnowledgeDoc::getStatus, DocStatus.PUBLISHED.name())
                .eq(KnowledgeDoc::getDeleted, 0)
                .orderByAsc(KnowledgeDoc::getId));
        if (docs == null || docs.isEmpty()) throw new BusinessException("没有已发布的知识文档");
        List<KnowledgeDocVersion> result = new ArrayList<>();
        for (KnowledgeDoc doc : docs) {
            KnowledgeDocVersion version = knowledgeDocVersionMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocVersion>()
                            .eq(KnowledgeDocVersion::getDocId, doc.getId())
                            .eq(KnowledgeDocVersion::getVersionNo, doc.getCurrentVersion()));
            if (version == null || !ReviewStatus.APPROVED.name().equals(version.getReviewStatus())) {
                throw new BusinessException("存在未审核通过的已发布文档版本，拒绝构建");
            }
            if (!snapshotId.equals(version.getMetadataSnapshotId())) {
                throw new BusinessException("已发布文档版本来源快照不一致，请先按所选快照重新生成、审核并发布");
            }
            snapshotFactValidator.validate(datasourceId, snapshotId, version.getContent());
            result.add(version);
        }
        return result;
    }

    /** Python owns chunking; every output chunk is revalidated and saved before build membership is frozen. */
    private Map<String, List<KnowledgeChunk>> chunkAndStoreBuildFacts(RagIndexBuild build) throws Exception {
        Map<String, Object> manifest = objectMapper.readValue(build.getManifestJson(), new TypeReference<>() {});
        Object rawVersions = manifest.get("docVersions");
        if (!(rawVersions instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalStateException("RAG build manifest 缺少已发布文档版本");
        }
        Map<String, List<KnowledgeChunk>> eligibleByDocumentVersion = new LinkedHashMap<>();
        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) throw new IllegalStateException("RAG build manifest 文档版本格式无效");
            Long docId = longValue(entry.get("docId"));
            Integer versionNo = intValue(entry.get("versionNo"));
            Long versionId = longValue(entry.get("versionId"));
            String expectedContentHash = stringValue(entry.get("contentHash"));
            KnowledgeDocVersion version = knowledgeDocVersionMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeDocVersion>()
                            .eq(KnowledgeDocVersion::getDocId, docId)
                            .eq(KnowledgeDocVersion::getVersionNo, versionNo));
            if (version == null || !Objects.equals(version.getId(), versionId)
                    || !Objects.equals(build.getSourceSnapshotId(), version.getMetadataSnapshotId())
                    || !ReviewStatus.APPROVED.name().equals(version.getReviewStatus())
                    || !Objects.equals(expectedContentHash, sha256(Objects.toString(version.getContent(), "")))) {
                throw new IllegalStateException("build 确认后的文档版本发生变化或来源快照不一致");
            }
            snapshotFactValidator.validate(build.getDatasourceId(), build.getSourceSnapshotId(), version.getContent());
            List<Map<String, Object>> payloads = pythonRagClient.chunkBuildDocument(
                    build, docId, versionNo, version.getContent());
            if (payloads == null || payloads.isEmpty()) {
                throw new IllegalStateException("Python 切片器未返回带来源 marker 的 chunk");
            }
            List<KnowledgeChunk> eligibleForDocument = new ArrayList<>();
            for (int index = 0; index < payloads.size(); index++) {
                Map<String, Object> payload = payloads.get(index);
                KnowledgeChunk chunk = toKnowledgeChunk(build, docId, versionNo, payload, index);
                knowledgeChunkMapper.insert(chunk);
                if (isBuildEligible(chunk)) {
                    RagIndexBuildChunk membership = new RagIndexBuildChunk();
                    membership.setBuildId(build.getBuildId());
                    membership.setChunkId(chunk.getId());
                    membership.setDatasourceId(build.getDatasourceId());
                    membership.setSourceSnapshotId(build.getSourceSnapshotId());
                    membership.setDocId(docId);
                    membership.setVersionNo(versionNo);
                    membership.setResourceDependencies(chunk.getResourceDependencies());
                    membership.setFactSourceIds(chunk.getFactSourceIds());
                    membership.setFactType(chunk.getFactType());
                    membership.setFactReviewStatus(chunk.getFactReviewStatus());
                    membership.setGovernanceStatus(chunk.getGovernanceStatus());
                    buildChunkMapper.insert(membership);
                    eligibleForDocument.add(chunk);
                }
            }
            if (!eligibleForDocument.isEmpty()) {
                eligibleByDocumentVersion.put(docId + ":" + versionNo, eligibleForDocument);
            }
        }
        return eligibleByDocumentVersion;
    }

    private KnowledgeChunk toKnowledgeChunk(RagIndexBuild build, Long docId, Integer versionNo,
                                            Map<String, Object> payload, int fallbackIndex) {
        Long sourceSnapshotId = longValue(payload.get("sourceSnapshotId"));
        List<String> resourceDependencies = stringList(payload.get("resourceDependencies"));
        List<String> factSourceIds = stringList(payload.get("factSourceIds"));
        String factType = stringValue(payload.get("factType"));
        String factReviewStatus = stringValue(payload.get("factReviewStatus"));
        String reviewStatus = stringValue(payload.get("reviewStatus"));
        String governanceStatus = stringValue(payload.get("governanceStatus"));
        if (!Objects.equals(sourceSnapshotId, build.getSourceSnapshotId())
                || resourceDependencies.isEmpty() || factSourceIds.isEmpty()
                || factType == null || factType.isBlank()
                || factReviewStatus == null || reviewStatus == null || governanceStatus == null) {
            throw new IllegalStateException("Python chunk 缺少同快照来源、审核状态或完整依赖");
        }
        return KnowledgeChunk.builder()
                .docId(docId)
                .versionNo(versionNo)
                .chunkIndex(intValue(payload.get("chunkIndex")) == null ? fallbackIndex : intValue(payload.get("chunkIndex")))
                .chunkGroupId(stringValue(payload.get("chunkGroupId")))
                .metadataSnapshotId(sourceSnapshotId)
                .chunkType(stringValue(payload.get("chunkType")))
                .chunkText(stringValue(payload.get("chunkText")))
                .relatedTable(stringValue(payload.get("tableName")))
                .relatedColumn(stringValue(payload.get("relatedColumn")))
                .relatedTables(writeJson(stringList(payload.get("relatedTables"))))
                .relatedColumns(writeJson(stringList(payload.get("relatedColumns"))))
                .entityIds(writeJson(intList(payload.get("entityIds"))))
                .trustScore(intValue(payload.get("trustScore")))
                .contentHash(stringValue(payload.get("contentHash")))
                .resourceDependencies(writeJson(resourceDependencies))
                .factSourceIds(writeJson(factSourceIds))
                .factType(factType)
                .factReviewStatus(factReviewStatus)
                .governanceStatus(governanceStatus)
                .reviewStatus(reviewStatus)
                .vectorStatus("PENDING")
                .build();
    }

    private boolean isBuildEligible(KnowledgeChunk chunk) {
        return ReviewStatus.APPROVED.name().equals(chunk.getReviewStatus())
                && ReviewStatus.APPROVED.name().equals(chunk.getFactReviewStatus())
                && INDEXABLE_GOVERNANCE.contains(chunk.getGovernanceStatus());
    }

    private List<String> stringList(Object raw) {
        if (raw instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<>() {});
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    private List<Integer> intList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(this::intValue).filter(Objects::nonNull).toList();
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("文档版本摘要计算失败", e);
        }
    }

    private AiConfigVO.EmbeddingConfig targetEmbeddingConfig() {
        AiConfigVO config = aiConfigService.getConfig();
        AiConfigVO.EmbeddingConfig target = config.getPendingEmbedding() != null
                ? config.getPendingEmbedding() : config.getActiveEmbedding();
        if (target == null || target.getProviderId() == null || target.getModel() == null
                || target.getDimension() == null || target.getDimension() <= 0) {
            throw new BusinessException("Embedding 目标配置不完整");
        }
        String key = aiConfigService.decryptApiKey(aiConfigService.getProvider(target.getProviderId(), true));
        if (key == null || key.isBlank()) throw new BusinessException("Embedding 供应商凭据缺失，无法构建 RAG");
        return target;
    }

    private Map<String, Object> executionEmbeddingConfig(RagIndexBuild build) {
        AiConfigVO.Provider provider = aiConfigService.getProvider(build.getEmbeddingProviderId(), true);
        String apiKey = aiConfigService.decryptApiKey(provider);
        if (apiKey == null || apiKey.isBlank()) throw new BusinessException("RAG build 的 Embedding 凭据不可用");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("providerId", build.getEmbeddingProviderId());
        config.put("baseUrl", build.getEmbeddingBaseUrl());
        config.put("apiKey", apiKey);
        config.put("model", build.getEmbeddingModel());
        config.put("dimension", build.getEmbeddingDimension());
        return config;
    }

    private String currentTargetFingerprint() {
        AiConfigVO.EmbeddingConfig target = targetEmbeddingConfig();
        return embeddingFingerprint(target);
    }

    private String embeddingFingerprint(AiConfigVO.EmbeddingConfig config) {
        String baseUrl = providerBaseUrl(config.getProviderId());
        String value = String.join("|",
                Objects.toString(config.getProviderId(), ""),
                Objects.toString(config.getModel(), ""),
                Objects.toString(baseUrl, ""),
                Objects.toString(config.getDimension(), ""));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 配置指纹生成失败", e);
        }
    }

    private String providerBaseUrl(String providerId) {
        AiConfigVO.Provider provider = aiConfigService.getProvider(providerId, true);
        return Objects.toString(provider.getBaseUrl(), "");
    }

    private String collectionName(Long datasourceId, String buildId) {
        return "dataocean_rag_ds" + datasourceId + "_b" + buildId.replace("-", "").toLowerCase();
    }

    private Map<String, Object> buildManifest(Long datasourceId, Long snapshotId, List<KnowledgeDocVersion> versions) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("datasourceId", datasourceId);
        manifest.put("sourceSnapshotId", snapshotId);
        manifest.put("docVersions", versions.stream()
                .sorted(Comparator.comparing(KnowledgeDocVersion::getDocId).thenComparing(KnowledgeDocVersion::getVersionNo))
                .map(version -> Map.of(
                        "docId", version.getDocId(),
                        "versionNo", version.getVersionNo(),
                        "versionId", version.getId(),
                        "contentHash", sha256(Objects.toString(version.getContent(), ""))))
                .toList());
        manifest.put("expectedChunkCount", 0);
        return manifest;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException("RAG build manifest 序列化失败");
        }
    }

    private int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "RAG build failed" : message.substring(0, Math.min(900, message.length()));
    }
}
