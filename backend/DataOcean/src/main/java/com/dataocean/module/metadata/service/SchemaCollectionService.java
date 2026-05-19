package com.dataocean.module.metadata.service;

public interface SchemaCollectionService {

    Long executeFullSync(Long datasourceId, boolean includeStatistics);

    Long executeScheduledFullSync(Long datasourceId, boolean includeStatistics);
}
