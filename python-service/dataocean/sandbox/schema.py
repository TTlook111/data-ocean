"""SQL 沙箱请求/响应模型"""

from __future__ import annotations

from pydantic import BaseModel, Field
from pydantic import AliasChoices


class RowFilterItem(BaseModel):
    """行级过滤条件"""

    table_name: str = Field(validation_alias=AliasChoices("table_name", "tableName"))
    condition: str


class ValidateRequest(BaseModel):
    """SQL 校验请求"""

    sql: str
    datasource_id: int = Field(validation_alias=AliasChoices("datasource_id", "datasourceId"))
    allowed_tables: list[str] = Field(
        default_factory=list,
        validation_alias=AliasChoices("allowed_tables", "allowedTables"),
    )
    # 安全修复：显式声明表访问范围模式
    # UNSPECIFIED: 未提供权限上下文（默认），应拒绝访问
    # ALLOWLIST: 使用 allowed_tables 白名单
    # UNRESTRICTED: 显式全库开放（需 Java 显式传递）
    table_scope_mode: str = Field(
        default="UNSPECIFIED",
        validation_alias=AliasChoices("table_scope_mode", "tableScopeMode"),
    )
    row_filters: list[RowFilterItem] = Field(
        default_factory=list,
        validation_alias=AliasChoices("row_filters", "rowFilters"),
    )
    denied_columns: dict[str, list[str]] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("denied_columns", "deniedColumns"),
    )
    mask_columns: dict[str, list[str]] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("mask_columns", "maskColumns"),
    )
    mask_strategies: dict[str, str] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("mask_strategies", "maskStrategies"),
    )


class ValidateResponse(BaseModel):
    """SQL 校验响应"""

    passed: bool
    violations: list[str] = Field(default_factory=list)
    rewritten_sql: str | None = Field(default=None, serialization_alias="rewrittenSql")
    masked_columns: dict[str, str] = Field(default_factory=dict, serialization_alias="maskedColumns")


class ConnectionConfig(BaseModel):
    """数据源连接配置"""

    host: str
    port: int = 3306
    database: str
    username: str
    encrypted_password: str = Field(
        default="",
        validation_alias=AliasChoices("encrypted_password", "encryptedPassword"),
    )


class ExecuteRequest(BaseModel):
    """SQL 执行请求"""

    sql: str
    datasource_id: int = Field(validation_alias=AliasChoices("datasource_id", "datasourceId"))
    connection_config: ConnectionConfig = Field(
        validation_alias=AliasChoices("connection_config", "connectionConfig"),
    )
    mask_columns: dict[str, str] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("mask_columns", "maskColumns"),
    )


class ExecuteResponse(BaseModel):
    """SQL 执行响应"""

    success: bool
    data: list[dict] | None = None
    columns: list[dict] | None = None
    row_count: int = Field(default=0, serialization_alias="rowCount")
    execution_time_ms: int = Field(default=0, serialization_alias="executionTimeMs")
    error: str | None = None
    error_type: str | None = Field(default=None, serialization_alias="errorType")
    truncated: bool = False
    masked_columns: dict[str, str] = Field(default_factory=dict, serialization_alias="maskedColumns")
