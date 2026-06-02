"""知识库模块的请求/响应数据模型"""

from pydantic import BaseModel


class TableMetadata(BaseModel):
    """表元数据"""

    table_name: str
    table_comment: str | None = None
    columns: list["ColumnMetadata"] = []


class ColumnMetadata(BaseModel):
    """字段元数据"""

    column_name: str
    column_type: str
    column_comment: str | None = None
    is_primary_key: bool = False
    confidence_score: int | None = None
    governance_status: str | None = None
    tags: list[str] = []
    is_indexed: bool = False


class GenerateDraftRequest(BaseModel):
    """生成 skills.md 草稿请求"""

    snapshot_id: int
    datasource_id: int
    tables_metadata: list[TableMetadata]
    foreign_keys: list[dict] = []
    indexes: list[dict] = []


class GenerateDraftResponse(BaseModel):
    """生成 skills.md 草稿响应"""

    content: str
    generation_source: str = "AI_GENERATED"
    warnings: list[str] = []


# ---- 域分析 + 批量生成 ----


class DomainGroup(BaseModel):
    """AI 识别出的业务域"""

    domain_name: str
    table_names: list[str]
    reason: str


class DomainDoc(BaseModel):
    """单个域生成的 skills.md 文档"""

    title: str
    content: str
    table_names: list[str]
    warnings: list[str] = []


class BatchGenerateResponse(BaseModel):
    """批量生成响应"""

    docs: list[DomainDoc]
    total_domains: int
