package com.dataocean.module.knowledge.client;

import java.util.Map;

/** Java 已筛选的快照生成与知识候选调用。 */
public interface PythonKnowledgeClient {
    Map<String, Object> generateBusinessCandidate(Map<String, Object> request);
    Map<String, Object> generateSnapshotDraft(Map<String, Object> request);
    Map<String, Object> supplementSemantics(Map<String, Object> request);
    Map<String, Object> generateFieldSemanticCandidate(Map<String, Object> request);
}
