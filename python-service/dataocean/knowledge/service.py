"""知识库草稿生成服务

基于元数据快照调用 LLM 生成结构化的 skills.md 业务知识文档。
"""

import asyncio
import logging
from pathlib import Path

import httpx
from jinja2 import Template

from dataocean.core.config import settings
from dataocean.core.exceptions import LLMException

from .schema import GenerateDraftRequest, GenerateDraftResponse

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).parent / "prompts"


async def generate_draft(request: GenerateDraftRequest) -> GenerateDraftResponse:
    """基于元数据快照生成 skills.md 草稿

    Args:
        request: 包含快照元数据的请求对象

    Returns:
        生成的草稿内容和警告信息
    """
    logger.info(
        "开始生成 skills.md 草稿 snapshot_id=%d datasource_id=%d",
        request.snapshot_id,
        request.datasource_id,
    )

    # 加载 Jinja2 Prompt 模板
    template_path = PROMPTS_DIR / "skills_md_template.j2"
    template = Template(template_path.read_text(encoding="utf-8"))

    # 填充模板变量
    prompt = template.render(
        tables=request.tables_metadata,
        foreign_keys=request.foreign_keys,
        indexes=request.indexes,
    )

    # 调用 Qwen API 生成草稿
    content = await _call_llm(prompt)

    # 检查无注释字段并标记警告
    warnings = _check_missing_comments(request.tables_metadata)

    logger.info(
        "skills.md 草稿生成完成 content_length=%d warnings=%d",
        len(content),
        len(warnings),
    )

    return GenerateDraftResponse(
        content=content,
        generation_source="AI_GENERATED",
        warnings=warnings,
    )


async def _call_llm(prompt: str) -> str:
    """调用 Qwen API 生成内容（含重试机制）

    最多重试 settings.llm_max_retries 次，每次重试间隔递增。
    超时或网络错误时自动重试，其他错误直接抛出。

    Args:
        prompt: 完整的 Prompt 文本

    Returns:
        LLM 生成的 Markdown 内容

    Raises:
        LLMException: 重试耗尽后抛出
    """
    max_retries = settings.llm_max_retries

    for attempt in range(max_retries + 1):
        try:
            async with httpx.AsyncClient(timeout=float(settings.llm_timeout)) as client:
                response = await client.post(
                    f"{settings.dashscope_base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {settings.dashscope_api_key}"},
                    json={
                        "model": settings.qwen_model,
                        "messages": [
                            {
                                "role": "system",
                                "content": "你是一个数据库文档专家，负责根据数据库元数据生成结构化的 skills.md 业务知识文档。",
                            },
                            {"role": "user", "content": prompt},
                        ],
                        "temperature": settings.llm_temperature,
                    },
                )
                response.raise_for_status()
                data = response.json()
                return data["choices"][0]["message"]["content"]
        except (httpx.TimeoutException, httpx.ConnectError, httpx.ReadError) as e:
            if attempt < max_retries:
                wait_seconds = (attempt + 1) * 5
                logger.warning(
                    "LLM 调用失败，%d 秒后重试 attempt=%d/%d reason=%s",
                    wait_seconds, attempt + 1, max_retries + 1, str(e),
                )
                await asyncio.sleep(wait_seconds)
            else:
                logger.error("LLM 调用重试耗尽 attempts=%d", max_retries + 1)
                raise LLMException("AI 草稿生成失败：重试耗尽")

    raise LLMException("AI 草稿生成失败")


def _check_missing_comments(tables: list) -> list[str]:
    """检查无注释的表和字段，生成警告列表"""
    warnings = []
    for table in tables:
        if not table.table_comment:
            warnings.append(
                f"表 {table.table_name} 无注释，AI 已基于字段推测用途（待人工确认）"
            )
        for col in table.columns:
            if not col.column_comment:
                warnings.append(
                    f"字段 {table.table_name}.{col.column_name} 无注释（待人工确认）"
                )
    return warnings
