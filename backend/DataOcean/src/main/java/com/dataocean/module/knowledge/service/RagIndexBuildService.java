package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.entity.RagIndexBuild;

import java.util.List;
import java.util.Map;

/** Datasource-level, explicitly confirmed RAG build lifecycle. */
public interface RagIndexBuildService {
    RagIndexBuild confirmBuild(Long datasourceId, Long snapshotId, Long userId, boolean confirmed);

    List<RagIndexBuild> listForDatasource(Long datasourceId);

    RagIndexBuild activeBuild(Long datasourceId);

    /** Locks the active pointer until the caller commits its query task row. */
    RagIndexBuild activeBuildForQuery(Long datasourceId);

    /** Decrypted provider key for one request; never persist this map. */
    Map<String, Object> embeddingConfigForQuery(RagIndexBuild build);

    void processQueuedBuilds();

    void cleanupSupersededBuilds();
}
