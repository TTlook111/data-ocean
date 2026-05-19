package com.dataocean.module.metadata.service;

public interface SchemaCollectionService {

    void executeFullSync(Long datasourceId, Long triggeredBy, boolean includeStatistics);
}
