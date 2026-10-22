package com.dataocean.module.fieldsemantic.entity.vo;

import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticBinding;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticCandidate;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticReview;
import com.dataocean.module.fieldsemantic.entity.po.FieldSemanticRevision;
import lombok.Data;

import java.util.List;

@Data
public class FieldSemanticWorkspaceVO {
    private Long datasourceId;
    private Long snapshotId;
    private Long tableMetaId;
    private Long columnMetaId;
    private String databaseName;
    private String tableName;
    private String columnName;
    private String dataType;
    private String rawColumnComment;
    private String governanceStatus;
    private Long definitionId;
    private Integer rowVersion;
    private Long currentBindingId;
    private Long currentApprovedRevisionId;
    private Long workingRevisionId;
    private String workingStatus;
    private String definitionStatus;
    private String bindingStatus;
    private String bindingReason;
    private String effectiveStatus;
    private FieldSemanticRevision currentApproved;
    private FieldSemanticRevision draft;
    private List<FieldSemanticRevision> revisions;
    private List<FieldSemanticReview> reviews;
    private List<FieldSemanticBinding> bindings;
    private List<FieldSemanticCandidate> candidates;
}
