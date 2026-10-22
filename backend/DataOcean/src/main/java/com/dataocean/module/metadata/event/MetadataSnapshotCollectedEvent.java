package com.dataocean.module.metadata.event;

/** Published after a complete metadata snapshot commits; consumers create pending, never inherited, bindings. */
public record MetadataSnapshotCollectedEvent(Long datasourceId, Long snapshotId, Long triggeredBy) { }
