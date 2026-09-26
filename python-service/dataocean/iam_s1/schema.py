"""严格的 Java -> Python IAM-SIMPLE-1 合同。

S1 使用 camelCase JSON 名称且禁止额外字段。没有旧字段别名、平铺权限
结构或可选的安全字段；绑定值只存在于执行请求，不存在于快照或模型上下文。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class S1Model(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=False)


class S1Column(S1Model):
    name: str
    columnId: int
    # 必须与 Java 侧 IamS1ColumnUsage 完全一致：Java 枚举能产出的每个取值
    # 这里都要接受，否则整份快照会被 Literal 校验直接拒绝。
    usage: list[Literal["PROJECTION", "FILTER", "JOIN", "ORDER", "GROUP", "HAVING", "FUNCTION", "SUBQUERY"]]
    protectionLevel: Literal["NORMAL", "HIDDEN", "MASKED"]
    maskPolicy: str | None
    dataType: str | None
    governanceStatus: str
    sourceSnapshotId: int


class S1Predicate(S1Model):
    columnName: str
    columnId: int
    operatorCode: Literal["EQ", "NE", "GT", "GE", "LT", "LE", "IN", "NOT_IN", "IS_NULL", "IS_NOT_NULL"]
    valueType: Literal[
        "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME",
        "STRING_LIST", "INTEGER_LIST", "DECIMAL_LIST", "NULL",
    ]
    parameterReference: str | None = None
    bindingReference: str


class S1RowCondition(S1Model):
    matchType: Literal["ALL", "ANY"]
    predicates: list[S1Predicate]


class S1GrantSource(S1Model):
    grantId: int
    subjectType: str | None = None
    subjectId: int | None = None
    sourceSummary: str
    departmentScope: str | None = None
    grantSource: str
    sourceReferenceId: int | None = None
    validFrom: str | None = None
    validUntil: str | None = None
    explicitColumns: list[str]
    rowCondition: S1RowCondition | None = None


class S1Resource(S1Model):
    tableName: str
    columns: list[S1Column]
    grantSources: list[S1GrantSource]
    sourceSnapshotId: int


class S1Capabilities(S1Model):
    query: bool
    viewSql: bool
    export: bool


class S1PermissionSnapshot(S1Model):
    protocolVersion: Literal["IAM-SIMPLE-1"]
    taskId: str
    userId: int
    datasourceId: int
    activeMetadataSnapshotId: int
    permissionRevision: int
    calculatedAt: str
    nextEffectiveAt: str | None
    resources: list[S1Resource]
    capabilities: S1Capabilities


class S1ExecutionBinding(S1Model):
    reference: str
    valueType: str
    value: Any


class S1ConversationTurn(S1Model):
    role: Literal["user", "assistant"]
    content: str


class S1CandidatePredicate(S1Model):
    columnMetaId: int
    columnName: str
    operatorCode: Literal["EQ", "NE", "GT", "GE", "LT", "LE", "IN", "NOT_IN", "IS_NULL", "IS_NOT_NULL"]
    valueType: Literal[
        "STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME",
        "STRING_LIST", "INTEGER_LIST", "DECIMAL_LIST", "NULL",
    ]
    parameterReference: str | None = None
    bindingReference: str


class S1CandidateRowCondition(S1Model):
    matchType: Literal["ALL", "ANY"]
    predicates: list[S1CandidatePredicate]


class S1CandidateGrantSource(S1Model):
    grantId: int
    subjectType: str | None = None
    subjectId: int | None = None
    sourceSummary: str
    departmentScope: str | None = None
    grantSource: str
    sourceReferenceId: int | None = None
    validFrom: str | None = None
    validUntil: str | None = None
    explicitColumns: list[str]
    rowCondition: S1CandidateRowCondition | None = None


class S1CandidateColumn(S1Model):
    columnMetaId: int
    columnName: str
    columnComment: str | None = None
    dataType: str | None = None
    governanceStatus: str
    protectionLevel: Literal["NORMAL", "HIDDEN", "MASKED"]
    maskPolicy: str | None = None
    allowedUsages: list[Literal["PROJECTION", "FILTER", "JOIN", "ORDER", "GROUP", "HAVING", "FUNCTION", "SUBQUERY"]] = Field(min_length=1)
    grantSources: list[S1CandidateGrantSource] = Field(min_length=1)


class S1CandidateTable(S1Model):
    tableName: str
    tableComment: str | None = None
    governanceStatus: str
    columns: list[S1CandidateColumn] = Field(min_length=1)


class S1CandidateCatalog(S1Model):
    datasourceId: int
    activeMetadataSnapshotId: int
    permissionRevision: int
    tables: list[S1CandidateTable]


class S1ConnectionConfig(S1Model):
    host: str
    port: int
    database: str
    username: str
    password: str


class S1QueryExecuteRequest(S1Model):
    protocolVersion: Literal["IAM-SIMPLE-1"]
    taskId: str
    userId: int
    datasourceId: int
    activeMetadataSnapshotId: int
    permissionRevision: int
    permissionSnapshot: S1PermissionSnapshot | None = None
    executionBindings: list[S1ExecutionBinding] = Field(default_factory=list)
    ragBuildId: str | None = None
    ragSourceSnapshotId: int | None = None
    ragCollectionName: str | None = None
    ragEmbeddingConfig: dict[str, Any] | None = None
    conversationId: int | None = None
    conversationThreadId: str | None = None
    candidateCatalog: S1CandidateCatalog | None = None
    capabilities: S1Capabilities | None = None
    deadlineEpochSeconds: float | None = None
    resume: bool = False
    sqlAttemptsUsed: int = 0
    llmCallsUsed: int = 0
    resumeProtectedResult: dict[str, Any] | None = None
    question: str = Field(min_length=1, max_length=500)
    connectionConfig: S1ConnectionConfig | None = None
    conversationHistory: list[S1ConversationTurn]
    conversationSummary: dict[str, Any] | None
    ragChunks: list[dict[str, Any]]
    fallbackChunks: list[dict[str, Any]]
    glossaryTerms: list[dict[str, Any]]
    fewShotExamples: list[dict[str, Any]]


class S1SqlValidateRequest(S1Model):
    protocolVersion: Literal["IAM-SIMPLE-1"]
    taskId: str
    userId: int
    datasourceId: int
    activeMetadataSnapshotId: int
    permissionRevision: int
    permissionSnapshot: S1PermissionSnapshot
    executionBindings: list[S1ExecutionBinding]
    sql: str = Field(min_length=1)


class S1SqlExecuteRequest(S1Model):
    protocolVersion: Literal["IAM-SIMPLE-1"]
    taskId: str
    userId: int
    datasourceId: int
    activeMetadataSnapshotId: int
    permissionRevision: int
    permissionSnapshot: S1PermissionSnapshot
    executionBindings: list[S1ExecutionBinding]
    originalSql: str = Field(min_length=1)
    validatedSql: str = Field(min_length=1)
    connectionConfig: S1ConnectionConfig


class S1RagRetrieveRequest(S1Model):
    protocolVersion: Literal["IAM-SIMPLE-1"]
    taskId: str
    userId: int
    datasourceId: int
    activeMetadataSnapshotId: int
    permissionRevision: int
    permissionSnapshot: S1PermissionSnapshot
    question: str = Field(min_length=1, max_length=500)
    chunks: list[dict[str, Any]]
    ragBuildId: str | None = None
    ragSourceSnapshotId: int | None = None
    ragCollectionName: str | None = None
    ragEmbeddingConfig: dict[str, Any] | None = None
