package com.dataocean.module.fieldsemantic.service;
import java.util.*;
public interface GovernanceFieldProfileService {
    Map<String,Object> read(String taskId,List<String> fields);
    List<Map<String,Object>> feedbackTargets(String taskId,Long userId);
    Map<String,Object> current(Long snapshotId,Long columnId,Long userId);
    Map<String,Object> evidence(Long snapshotId,Long columnId,Long userId,com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery query);
}
