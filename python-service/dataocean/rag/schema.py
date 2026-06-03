"""RAG 模块请求/响应数据模型"""

from typing import Any

from pydantic import (
    AliasChoices,
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)


class RagBaseModel(BaseModel):
    """RAG DTO 基类：兼容 Java camelCase 和 Python snake_case。"""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class ColumnInfo(RagBaseModel):
    """召回字段信息"""

    name: str = Field(validation_alias=AliasChoices("name", "column_name", "columnName"))
    type: str = Field(
        default="",
        validation_alias=AliasChoices("type", "column_type", "columnType"),
    )
    comment: str = Field(
        default="",
        validation_alias=AliasChoices("comment", "column_comment", "columnComment"),
    )
    trust_score: int | None = Field(
        default=None,
        validation_alias=AliasChoices("trust_score", "trustScore", "confidence_score", "confidenceScore"),
        serialization_alias="trustScore",
    )


class ChunkItem(RagBaseModel):
    """向量化写入的单个切片"""

    source_id: int | None = Field(
        default=None,
        validation_alias=AliasChoices("source_id", "sourceId"),
        serialization_alias="sourceId",
    )
    chunk_type: str = Field(
        validation_alias=AliasChoices("chunk_type", "chunkType"),
        serialization_alias="chunkType",
    )
    chunk_text: str = Field(
        validation_alias=AliasChoices("chunk_text", "chunkText"),
        serialization_alias="chunkText",
    )
    related_table: str = Field(
        default="",
        validation_alias=AliasChoices("related_table", "relatedTable", "table_name", "tableName"),
        serialization_alias="tableName",
    )
    related_column: str = Field(
        default="",
        validation_alias=AliasChoices("related_column", "relatedColumn", "column_name", "columnName"),
        serialization_alias="relatedColumn",
    )
    governance_status: str = Field(
        default="NORMAL",
        validation_alias=AliasChoices("governance_status", "governanceStatus"),
        serialization_alias="governanceStatus",
    )
    review_status: str = Field(
        default="APPROVED",
        validation_alias=AliasChoices("review_status", "reviewStatus"),
        serialization_alias="reviewStatus",
    )
    trust_score: int | None = Field(
        default=None,
        validation_alias=AliasChoices("trust_score", "trustScore"),
        serialization_alias="trustScore",
    )


class EmbeddingConfig(RagBaseModel):
    """向量化使用的 Embedding 配置。

    普通查询侧使用全局 active 配置；pending 索引构建时由 Java 显式传入。
    """

    provider_id: str | None = Field(
        default=None,
        validation_alias=AliasChoices("provider_id", "providerId"),
        serialization_alias="providerId",
    )
    base_url: str | None = Field(
        default=None,
        validation_alias=AliasChoices("base_url", "baseUrl"),
        serialization_alias="baseUrl",
    )
    api_key: str | None = Field(
        default=None,
        validation_alias=AliasChoices("api_key", "apiKey"),
        serialization_alias="apiKey",
    )
    model: str
    dimension: int | None = None


class VectorizeRequest(RagBaseModel):
    """向量化写入请求"""

    datasource_id: int = Field(
        gt=0,
        validation_alias=AliasChoices("datasource_id", "datasourceId"),
        serialization_alias="datasourceId",
    )
    snapshot_id: int = Field(
        gt=0,
        validation_alias=AliasChoices("snapshot_id", "snapshotId", "metadata_snapshot_id", "metadataSnapshotId"),
        serialization_alias="metadataSnapshotId",
    )
    version_no: int = Field(
        default=0,
        validation_alias=AliasChoices("version_no", "versionNo", "knowledge_version_no", "knowledgeVersionNo"),
        serialization_alias="knowledgeVersionNo",
    )
    doc_id: int | None = Field(
        default=None,
        validation_alias=AliasChoices("doc_id", "docId", "source_doc_id", "sourceDocId"),
        serialization_alias="docId",
    )
    previous_version_no: int | None = Field(
        default=None,
        validation_alias=AliasChoices("previous_version_no", "previousVersionNo"),
        serialization_alias="previousVersionNo",
    )
    chunks: list[ChunkItem]
    force: bool = False
    task_id: int | None = Field(
        default=None,
        validation_alias=AliasChoices("task_id", "taskId"),
        serialization_alias="taskId",
    )
    target_type: str | None = Field(
        default=None,
        validation_alias=AliasChoices("target_type", "targetType"),
        serialization_alias="targetType",
    )
    is_pending: bool = Field(
        default=False,
        validation_alias=AliasChoices("is_pending", "isPending"),
        serialization_alias="isPending",
    )
    index_version: str | None = Field(
        default=None,
        validation_alias=AliasChoices("index_version", "indexVersion"),
        serialization_alias="indexVersion",
    )
    target_collection: str | None = Field(
        default=None,
        validation_alias=AliasChoices("target_collection", "targetCollection"),
        serialization_alias="targetCollection",
    )
    target_dimension: int | None = Field(
        default=None,
        validation_alias=AliasChoices("target_dimension", "targetDimension"),
        serialization_alias="targetDimension",
    )
    embedding_config: EmbeddingConfig | None = Field(
        default=None,
        validation_alias=AliasChoices("embedding_config", "embeddingConfig"),
        serialization_alias="embeddingConfig",
    )


class VectorizeResponse(RagBaseModel):
    """向量化写入响应"""

    task_id: int | None = Field(
        default=None,
        validation_alias=AliasChoices("task_id", "taskId"),
        serialization_alias="taskId",
    )
    status: str = "COMPLETED"
    success_count: int = Field(
        default=0,
        validation_alias=AliasChoices("success_count", "successCount", "vectorizedCount"),
        serialization_alias="vectorizedCount",
    )
    failed_count: int = Field(
        default=0,
        validation_alias=AliasChoices("failed_count", "failedCount"),
        serialization_alias="failedCount",
    )
    errors: list[str] = Field(default_factory=list)
    failed_chunks: list[dict[str, Any]] = Field(
        default_factory=list,
        validation_alias=AliasChoices("failed_chunks", "failedChunks"),
        serialization_alias="failedChunks",
    )
    vector_ids: list[int] = Field(
        default_factory=list,
        validation_alias=AliasChoices("vector_ids", "vectorIds"),
        serialization_alias="vectorIds",
    )
    duration_ms: int = Field(
        default=0,
        validation_alias=AliasChoices("duration_ms", "durationMs"),
        serialization_alias="durationMs",
    )


class RetrieveRequest(RagBaseModel):
    """RAG 检索请求"""

    datasource_id: int = Field(
        gt=0,
        validation_alias=AliasChoices("datasource_id", "datasourceId"),
        serialization_alias="datasourceId",
    )
    question: str = Field(validation_alias=AliasChoices("question", "query"))
    top_k: int = Field(
        default=10,
        gt=0,
        le=20,
        validation_alias=AliasChoices("top_k", "topK"),
        serialization_alias="topK",
    )
    min_score: float | None = Field(
        default=None,
        ge=0,
        validation_alias=AliasChoices("min_score", "minScore"),
        serialization_alias="minScore",
    )
    active_snapshot_id: int = Field(
        gt=0,
        validation_alias=AliasChoices(
            "active_snapshot_id",
            "activeSnapshotId",
            "snapshot_id",
            "snapshotId",
            "metadata_snapshot_id",
            "metadataSnapshotId",
        ),
        serialization_alias="activeSnapshotId",
    )
    confidence_scores: dict[str, int] | None = Field(
        default=None,
        validation_alias=AliasChoices("confidence_scores", "confidenceScores"),
        serialization_alias="confidenceScores",
    )
    fallback_chunks: list[dict[str, Any]] | None = Field(
        default=None,
        validation_alias=AliasChoices("fallback_chunks", "fallbackChunks"),
        serialization_alias="fallbackChunks",
    )


class RetrievedSchema(RagBaseModel):
    """单条检索结果"""

    table_name: str = Field(
        default="",
        validation_alias=AliasChoices("table_name", "tableName"),
        serialization_alias="tableName",
    )
    table_comment: str = Field(
        default="",
        validation_alias=AliasChoices("table_comment", "tableComment"),
        serialization_alias="tableComment",
    )
    columns: list[ColumnInfo] = Field(default_factory=list)
    score: float = Field(
        validation_alias=AliasChoices("score", "weightedScore", "relevanceScore"),
        serialization_alias="weightedScore",
    )
    relevance_score: float | None = Field(
        default=None,
        validation_alias=AliasChoices("relevance_score", "relevanceScore"),
        serialization_alias="relevanceScore",
    )
    chunk_type: str = Field(
        default="",
        validation_alias=AliasChoices("chunk_type", "chunkType"),
        serialization_alias="chunkType",
    )
    source_type: str = Field(
        default="SCHEMA",
        validation_alias=AliasChoices("source_type", "sourceType"),
        serialization_alias="sourceType",
    )
    source_version: int = Field(
        default=0,
        validation_alias=AliasChoices("source_version", "sourceVersion", "knowledge_version_no", "knowledgeVersionNo"),
        serialization_alias="knowledgeVersionNo",
    )
    snapshot_id: int | None = Field(
        default=None,
        validation_alias=AliasChoices("snapshot_id", "snapshotId", "metadata_snapshot_id", "metadataSnapshotId"),
        serialization_alias="snapshotId",
    )
    chunk_text: str = Field(
        default="",
        validation_alias=AliasChoices("chunk_text", "chunkText"),
        serialization_alias="chunkText",
    )
    governance_status: str = Field(
        default="",
        validation_alias=AliasChoices("governance_status", "governanceStatus"),
        serialization_alias="governanceStatus",
    )
    review_status: str = Field(
        default="",
        validation_alias=AliasChoices("review_status", "reviewStatus"),
        serialization_alias="reviewStatus",
    )

    @field_validator("columns", mode="before")
    @classmethod
    def normalize_columns(cls, value: Any) -> Any:
        if value is None:
            return []
        if isinstance(value, str):
            return [{"name": item.strip()} for item in value.split(",") if item.strip()]
        if isinstance(value, list):
            return [{"name": item} if isinstance(item, str) else item for item in value]
        return value

    @model_validator(mode="after")
    def fill_scores(self) -> "RetrievedSchema":
        if self.relevance_score is None:
            self.relevance_score = self.score
        return self


class RetrieveResponse(RagBaseModel):
    """RAG 检索响应"""

    results: list[RetrievedSchema] = Field(
        default_factory=list,
        validation_alias=AliasChoices("results", "contexts"),
        serialization_alias="contexts",
    )
    total_found: int = Field(
        default=0,
        validation_alias=AliasChoices("total_found", "totalFound"),
        serialization_alias="totalFound",
    )
    returned: int = 0
    degraded: bool = False
    degrade_reason: str = Field(
        default="",
        validation_alias=AliasChoices("degrade_reason", "degradeReason"),
        serialization_alias="degradeReason",
    )
    message: str = ""
    retrieval_time_ms: int = Field(
        default=0,
        validation_alias=AliasChoices("retrieval_time_ms", "retrievalTimeMs"),
        serialization_alias="retrievalTimeMs",
    )

    @model_validator(mode="after")
    def fill_counts(self) -> "RetrieveResponse":
        if self.results and self.total_found == 0:
            self.total_found = len(self.results)
        if self.results and self.returned == 0:
            self.returned = len(self.results)
        if self.degraded and self.message and not self.degrade_reason:
            self.degrade_reason = self.message
        return self


class DeleteVectorsRequest(RagBaseModel):
    """向量删除请求"""

    datasource_id: int | None = Field(
        default=None,
        gt=0,
        validation_alias=AliasChoices("datasource_id", "datasourceId"),
        serialization_alias="datasourceId",
    )
    snapshot_id: int | None = Field(
        default=None,
        gt=0,
        validation_alias=AliasChoices("snapshot_id", "snapshotId", "metadata_snapshot_id", "metadataSnapshotId"),
        serialization_alias="snapshotId",
    )
    doc_id: int | None = Field(
        default=None,
        gt=0,
        validation_alias=AliasChoices("doc_id", "docId"),
        serialization_alias="docId",
    )
    version_no: int | None = Field(
        default=None,
        ge=0,
        validation_alias=AliasChoices("version_no", "versionNo", "knowledge_version_no", "knowledgeVersionNo"),
        serialization_alias="knowledgeVersionNo",
    )

    @model_validator(mode="after")
    def require_filter(self) -> "DeleteVectorsRequest":
        if (
            self.datasource_id is None
            and self.snapshot_id is None
            and self.doc_id is None
            and self.version_no is None
        ):
            raise ValueError("至少需要提供 datasourceId、snapshotId、docId 或 knowledgeVersionNo")
        return self


class DeleteVectorsResponse(RagBaseModel):
    """向量删除响应"""

    deleted_count: int = Field(
        default=0,
        validation_alias=AliasChoices("deleted_count", "deletedCount"),
        serialization_alias="deletedCount",
    )
    duration_ms: int = Field(
        default=0,
        validation_alias=AliasChoices("duration_ms", "durationMs"),
        serialization_alias="durationMs",
    )
