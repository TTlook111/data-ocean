"""知识库草稿生成服务"""

import logging
from pathlib import Path

from jinja2 import Template

from .schema import GenerateDraftRequest, GenerateDraftResponse

logger = logging.getLogger(__name__)

# Prompt 模板路径
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
    """调用 Qwen API 生成内容

    Args:
        prompt: 完整的 Prompt 文本

    Returns:
        LLM 生成的 Markdown 内容
    """
    import os

    import httpx

    api_key = os.getenv("QWEN_API_KEY")
    model = os.getenv("QWEN_MODEL", "qwen-plus")

    async with httpx.AsyncClient(timeout=120.0) as client:
        response = await client.post(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            headers={"Authorization": f"Bearer {api_key}"},
            json={
                "model": model,
                "messages": [
                    {
                        "role": "system",
                        "content": "你是一个数据库文档专家，负责根据数据库元数据生成结构化的 skills.md 业务知识文档。",
                    },
                    {"role": "user", "content": prompt},
                ],
                "temperature": 0.3,
            },
        )
        response.raise_for_status()
        data = response.json()
        return data["choices"][0]["message"]["content"]


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
