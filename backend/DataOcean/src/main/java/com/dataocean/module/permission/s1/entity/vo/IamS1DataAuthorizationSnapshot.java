package com.dataocean.module.permission.s1.entity.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** S1 数据权限计算和实际权限预览共用的结果。 */
@Getter
public class IamS1DataAuthorizationSnapshot {
    private final boolean allowed;
    private final String reasonCode;
    private final String protocolVersion;
    private final Long userId;
    private final Long datasourceId;
    private final String datasourceName;
    private final Long activeMetadataSnapshotId;
    private final Long permissionRevision;
    private final LocalDateTime calculatedAt;
    private final LocalDateTime nextEffectiveAt;
    private final List<IamS1TablePermissionVO> tables;

    @JsonCreator
    public IamS1DataAuthorizationSnapshot(
            @JsonProperty("allowed") boolean allowed,
            @JsonProperty("reasonCode") String reasonCode,
            @JsonProperty("protocolVersion") String protocolVersion,
            @JsonProperty("userId") Long userId,
            @JsonProperty("datasourceId") Long datasourceId,
            @JsonProperty("datasourceName") String datasourceName,
            @JsonProperty("activeMetadataSnapshotId") Long activeMetadataSnapshotId,
            @JsonProperty("permissionRevision") Long permissionRevision,
            @JsonProperty("calculatedAt") LocalDateTime calculatedAt,
            @JsonProperty("nextEffectiveAt") LocalDateTime nextEffectiveAt,
            @JsonProperty("tables") List<IamS1TablePermissionVO> tables) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
        this.protocolVersion = protocolVersion;
        this.userId = userId;
        this.datasourceId = datasourceId;
        this.datasourceName = datasourceName;
        this.activeMetadataSnapshotId = activeMetadataSnapshotId;
        this.permissionRevision = permissionRevision;
        this.calculatedAt = calculatedAt;
        this.nextEffectiveAt = nextEffectiveAt;
        this.tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public static IamS1DataAuthorizationSnapshot deny(String reasonCode,
                                                       com.dataocean.module.permission.s1.entity.dto.IamS1DataAuthorizationRequestDTO request,
                                                       Long revision, String datasourceName) {
        return new IamS1DataAuthorizationSnapshot(false, reasonCode,
                request == null ? null : request.getProtocolVersion(),
                request == null ? null : request.getUserId(),
                request == null ? null : request.getDatasourceId(), datasourceName,
                request == null ? null : request.getActiveMetadataSnapshotId(), revision,
                request == null ? null : request.getCalculatedAt(), null, List.of());
    }
}
