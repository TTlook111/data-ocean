"""知识库草稿生成服务

基于元数据快照调用 LLM 生成结构化的 skills.md 业务知识文档。
LLM 调用统一走 infra.llm，模板渲染统一走 prompt.renderer（LangChain PromptTemplate）。
"""

import logging
from pathlib import Path

from dataocean.infra.llm import call_llm
from dataocean.prompt.renderer import render_template_file

from .schema import GenerateDraftRequest, GenerateDraftResponse

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).parent / "prompts"
_SKILLS_MD_TEMPLATE = PROMPTS_DIR / "skills_md_template.j2"

# skills.md 草稿生成的系统角色提示
_SYSTEM_PROMPT = "你是一个数据库文档专家，负责根据数据库元数据生成结构化的 skills.md 业务知识文档。"

_MAX_WARNINGS = 50


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

    # 渲染 Prompt 模板（统一走 LangChain PromptTemplate）
    prompt = render_template_file(
        _SKILLS_MD_TEMPLATE,
        tables=request.tables_metadata,
        foreign_keys=request.foreign_keys,
        indexes=request.indexes,
    )

    # 调用 LLM 生成草稿（统一走 infra.llm）
    content = await call_llm(system_prompt=_SYSTEM_PROMPT, user_prompt=prompt)

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
    """检查无注释的表和字段，生成警告列表（最多 _MAX_WARNINGS 条）"""
    warnings = []
    for table in tables:
        if len(warnings) >= _MAX_WARNINGS:
            remaining = sum(1 for t in tables for c in t.columns if not c.column_comment) - len(warnings)
            if remaining > 0:
                warnings.append(f"...及其他 {remaining} 个字段无注释")
            break
        if not table.table_comment:
            warnings.append(
                f"表 {table.table_name} 无注释，AI 已基于字段推测用途（待人工确认）"
            )
        for col in table.columns:
            if len(warnings) >= _MAX_WARNINGS:
                break
            if not col.column_comment:
                warnings.append(
                    f"字段 {table.table_name}.{col.column_name} 无注释（待人工确认）"
                )
    return warnings
