package com.dataocean.module.metadata.service;

public interface SchemaCollectionService {

    Long executeFullSync(Long datasourceId, Long triggeredBy, boolean includeStatistics);
}
