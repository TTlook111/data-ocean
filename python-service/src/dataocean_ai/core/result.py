"""普通 JSON 返回遵循跨端公共契约，流式及文件协议另行实现。"""

from pydantic import BaseModel


class Result[T](BaseModel):
    """接口包装与具体业务数据分开，保留明确的返回类型。"""

    # 统一成功码或所属契约的错误码。
    code: int

    # 可向调用方披露的反馈，不包含秘密或异常原文。
    message: str

    # 已声明的返回数据，失败时为空。
    data: T | None
