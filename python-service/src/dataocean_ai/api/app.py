"""可独立构建和启动的正式 API，业务工作流按所属批次接入。"""

import logging
from importlib.metadata import version
from typing import Literal
from uuid import UUID, uuid4

import uvicorn
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException
from starlette.middleware.base import RequestResponseEndpoint

from dataocean_ai.core.config import Settings
from dataocean_ai.core.logging import configure_logging
from dataocean_ai.core.result import Result

# 仅输出进程运行事实，不把日志替代业务审计。
logger = logging.getLogger(__name__)


class Health(BaseModel):
    """只描述本进程存活，不推断数据库、向量服务或执行器就绪。"""

    # 当前 HTTP 进程已经能够处理请求。
    status: Literal["UP"] = "UP"

    # 与 Java 及独立技术验证程序区分的正式服务名称。
    service: Literal["dataocean-ai"] = "dataocean-ai"

    # 由安装包元数据提供，避免与 pyproject 再维护一份版本。
    version: str


def create_app() -> FastAPI:
    """应用工厂让启动与测试复用同一条真实路由及公共响应边界。"""
    # 正式版本来自同一个已构建包，API 与后续 Worker 不各自定义版本。
    release = version("dataocean-ai")

    app = FastAPI(title="DataOcean AI", version=release)

    @app.middleware("http")
    async def correlate(request: Request, call_next: RequestResponseEndpoint) -> Response:
        """诊断编号仅用于跨端关联，不作为身份或权限依据。"""
        trace_id = request.headers.get("X-Trace-Id")
        if trace_id is not None:
            # 外部头必须先验证，避免把任意文本或秘密带入诊断关联。
            try:
                trace_id = str(UUID(trace_id))
            except ValueError:
                trace_id = None
        trace_id = trace_id or str(uuid4())
        request.state.trace_id = trace_id

        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/health", response_model=Result[Health])
    async def health() -> Result[Health]:
        """报告当前进程存活，不将未接入的 AI 工作流记为可执行。"""
        return Result(code=200, message="成功", data=Health(version=release))

    @app.exception_handler(HTTPException)
    async def http_error(_request: Request, error: HTTPException) -> JSONResponse:
        """未知路径及错误方法也使用公共包装，不披露内部异常详情。"""
        # 当前基线只开放健康入口，其余业务端点须在实现和核权后注册。
        message = "接口不存在" if error.status_code == 404 else "当前请求方法不支持"

        return JSONResponse(
            status_code=error.status_code,
            headers=error.headers,
            content={"code": error.status_code * 1000 + 1, "message": message, "data": None},
        )

    return app


def main() -> None:
    """使用中央配置启动宿主机进程，配置无效时交给启动边界明确失败。"""
    settings = Settings.from_environment()
    configure_logging(settings.log_level)
    logger.info("Python API 启动，service=dataocean-ai，port=%s", settings.port)
    uvicorn.run(
        create_app(), host=settings.host, port=settings.port, log_config=None, access_log=False
    )
