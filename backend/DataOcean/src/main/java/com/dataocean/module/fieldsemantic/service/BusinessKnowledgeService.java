package com.dataocean.module.fieldsemantic.service;

import com.dataocean.module.fieldsemantic.entity.dto.BusinessKnowledgeDraftDTO;
import java.util.*;

public interface BusinessKnowledgeService {
    List<Map<String, Object>> list(Long snapshotId, Long userId);
    Map<String, Object> save(Long snapshotId, BusinessKnowledgeDraftDTO request, Long userId);
    Map<String, Object> generate(Long snapshotId, BusinessKnowledgeDraftDTO request, Long userId);
    void action(Long id, Integer version, String action, String note, Long userId);
    List<Map<String, Object>> facts(Long datasourceId, Long snapshotId);
    void validateFact(Long datasourceId, Long snapshotId, Map<String,Object> marker, String body);
    boolean membershipValid(Long datasourceId, Long snapshotId, List<String> sourceIds);
    List<Map<String,Object>> starterDrafts(Long snapshotId,Long userId);
    Map<String,Object> generateFromRevision(Long revisionId,Long userId);
    Map<String,Object> generateFromRevision(Long revisionId,Long userId,Long governanceTaskId,String executionToken);
    Map<String,Object> progress(Long revisionId,Long userId);
    List<Map<String,Object>> history(Long revisionId,Long userId);
    Map<String,Object> sourceStatus(Long revisionId,Long userId);
    Map<String,Object> sourceStatus(Long revisionId,Long userId,List<String> dependencies);
}
