"""RAG 模块请求/响应数据模型"""

from pydantic import BaseModel


class ChunkItem(BaseModel):
    """向量化写入的单个切片"""

    chunk_type: str
    chunk_text: str
    related_table: str = ""
    related_column: str = ""
    governance_status: str = "NORMAL"
    review_status: str = "APPROVED"


class VectorizeRequest(BaseModel):
    """向量化写入请求"""

    datasource_id: int
    snapshot_id: int
    version_no: int = 0
    chunks: list[ChunkItem]
    force: bool = False


class VectorizeResponse(BaseModel):
    """向量化写入响应"""

    success_count: int = 0
    failed_count: int = 0
    errors: list[str] = []


class RetrieveRequest(BaseModel):
    """RAG 检索请求"""

    datasource_id: int
    question: str
    top_k: int = 10
    confidence_scores: dict[str, int] | None = None


class RetrievedSchema(BaseModel):
    """单条检索结果"""

    table_name: str
    columns: list[str] = []
    score: float
    chunk_type: str
    source_version: int = 0
    chunk_text: str = ""


class RetrieveResponse(BaseModel):
    """RAG 检索响应"""

    results: list[RetrievedSchema] = []
    degraded: bool = False
    message: str = ""
