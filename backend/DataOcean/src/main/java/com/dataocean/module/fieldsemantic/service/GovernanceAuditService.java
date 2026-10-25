package com.dataocean.module.fieldsemantic.service;
public interface GovernanceAuditService {
    void record(String type,Long id,String action,Object payload,Long userId);
    java.util.List<java.util.Map<String,Object>> history(String type,Long id);
}
