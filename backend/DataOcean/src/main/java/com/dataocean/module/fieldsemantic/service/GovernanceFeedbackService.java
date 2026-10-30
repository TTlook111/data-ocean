package com.dataocean.module.fieldsemantic.service;
import com.dataocean.module.fieldsemantic.entity.dto.GovernanceFeedbackDTO;
import com.dataocean.module.fieldsemantic.entity.query.GovernancePageQuery;
import java.util.*;
public interface GovernanceFeedbackService {
    Map<String,Object> submit(String taskId,Long userId,GovernanceFeedbackDTO request);
    Map<String,Object> own(String taskId,Long userId);
    Map<String,Object> list(Long datasourceId,Long userId,GovernancePageQuery query);
    void review(Long id,Integer version,boolean approved,Long columnId,String reasonCode,String note,Long userId);
    void review(Long id,Integer version,boolean approved,Long columnId,String reasonCode,String note,Long userId,Map<String,String> acceptance);
    void verifyResolution(Long id,com.dataocean.module.fieldsemantic.entity.dto.GovernanceFeedbackVerificationDTO request,Long userId);
    List<Map<String,Object>> history(Long id,Long userId);
}
