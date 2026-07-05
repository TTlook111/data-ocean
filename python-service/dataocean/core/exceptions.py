"""自定义异常基类

所有业务异常继承自 ServiceException，便于全局异常处理器统一捕获。
"""

from typing import Any


class ServiceException(Exception):
    """服务层业务异常基类"""

    def __init__(self, message: str, code: int = 500):
        self.message = message
        self.code = code
        super().__init__(message)


class LLMException(ServiceException):
    """LLM 调用异常（超时、限流、API 错误等）"""

    def __init__(self, message: str = "AI 服务调用失败"):
        super().__init__(message, code=502)


class ValidationException(ServiceException):
    """数据校验异常"""

    def __init__(self, message: str = "数据校验失败"):
        super().__init__(message, code=400)


class NotFoundException(ServiceException):
    """资源不存在异常"""

    def __init__(self, resource: str, resource_id: Any = None):
        detail = f" {resource_id}" if resource_id else ""
        super().__init__(f"{resource}不存在{detail}", code=404)


class AuthorizationException(ServiceException):
    """授权异常"""

    def __init__(self, message: str = "无访问权限"):
        super().__init__(message, code=403)
