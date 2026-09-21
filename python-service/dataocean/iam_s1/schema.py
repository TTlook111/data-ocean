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
    parameterReference: str | None
    bindingReference: str


class S1RowCondition(S1Model):
    matchType: Literal["ALL", "ANY"]
    predicates: list[S1Predicate]


class S1GrantSource(S1Model):
    grantId: int
    sourceSummary: str
    grantSource: str
    sourceReferenceId: int | None
    explicitColumns: list[str]
    rowCondition: S1RowCondition | None


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
    permissionSnapshot: S1PermissionSnapshot
    executionBindings: list[S1ExecutionBinding]
    question: str = Field(min_length=1, max_length=500)
    connectionConfig: S1ConnectionConfig
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
