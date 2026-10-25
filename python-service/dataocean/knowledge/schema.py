"""知识库模块的请求/响应数据模型"""

from pydantic import BaseModel, Field


class TableMetadata(BaseModel):
    """表元数据"""

    table_id: int | None = None
    source_snapshot_id: int | None = None
    table_name: str
    table_comment: str | None = None
    table_type: str | None = None
    governance_status: str | None = None
    columns: list["ColumnMetadata"] = Field(default_factory=list)


class ColumnMetadata(BaseModel):
    """字段元数据"""

    column_id: int | None = None
    source_snapshot_id: int | None = None
    column_name: str
    column_type: str
    column_comment: str | None = None
    is_primary_key: bool = False
    ordinal_position: int | None = None
    confidence_score: int | None = None
    governance_status: str | None = None
    tags: list[str] = Field(default_factory=list)
    is_indexed: bool = False


class GenerateDraftRequest(BaseModel):
    """生成 skills.md 草稿请求"""

    snapshot_id: int
    datasource_id: int
    tables_metadata: list[TableMetadata]
    foreign_keys: list[dict] = Field(default_factory=list)
    indexes: list[dict] = Field(default_factory=list)
    glossary_facts: list[dict] = Field(default_factory=list)
    field_semantic_facts: list[dict] = Field(default_factory=list)
    business_knowledge_facts: list[dict] = Field(default_factory=list)
    excluded_terms: list[dict] = Field(default_factory=list)
    lineage_facts: list[dict] = Field(default_factory=list)


class GenerateDraftResponse(BaseModel):
    """生成 skills.md 草稿响应"""

    content: str
    generation_source: str = "SNAPSHOT_GENERATED"
    warnings: list[str] = Field(default_factory=list)
    coverage: dict = Field(default_factory=dict)
