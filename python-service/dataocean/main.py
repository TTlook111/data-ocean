"""DataOcean AI 服务入口

统一注册所有模块路由，配置全局异常处理和生命周期事件。
"""

import logging

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from dataocean.core.config import settings
from dataocean.core.exceptions import ServiceException
from dataocean.core.logging import setup_logging
from dataocean.knowledge.router import router as knowledge_router
from dataocean.rag.router import router as rag_router
from dataocean.agent.router import router as agent_router
from dataocean.sandbox.router import router as sandbox_router
from dataocean.chart.router import router as chart_router
from dataocean.prompt.router import router as prompt_router

logger = logging.getLogger(__name__)

app = FastAPI(
    title="DataOcean AI Service",
    version="0.1.0",
    description="NL2SQL 智能数据查询平台 — Python AI 服务层",
)


# === 生命周期事件 ===


@app.on_event("startup")
async def on_startup() -> None:
    """服务启动时初始化日志和配置"""
    setup_logging(settings.log_level)
    logger.info("DataOcean AI Service 启动完成")


# === 全局异常处理 ===


@app.exception_handler(ServiceException)
async def service_exception_handler(request: Request, exc: ServiceException) -> JSONResponse:
    """统一处理业务异常，返回标准错误格式"""
    logger.warning("业务异常 path=%s message=%s", request.url.path, exc.message)
    return JSONResponse(status_code=exc.code, content={"message": exc.message})


# === 健康检查 ===


@app.get("/health", tags=["health"])
async def health() -> dict[str, str]:
    """公共健康检查端点"""
    return {"status": "ok"}


@app.get("/internal/health", tags=["health"])
async def internal_health() -> dict[str, str]:
    """内部健康检查端点（Java 网关调用）"""
    return {"status": "ok"}


# === 路由注册（统一 prefix 管理） ===

app.include_router(knowledge_router, prefix="/internal/knowledge", tags=["knowledge"])
app.include_router(rag_router, prefix="/internal/rag", tags=["rag"])
app.include_router(agent_router, prefix="/internal/query", tags=["agent"])
app.include_router(sandbox_router, prefix="/internal/sql", tags=["sandbox"])
app.include_router(chart_router, prefix="/internal/chart", tags=["chart"])
app.include_router(prompt_router, prefix="/internal/prompts", tags=["prompt"])
