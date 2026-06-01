"""知识库草稿生成服务

基于元数据快照调用 LLM 生成结构化的 skills.md 业务知识文档。
LLM 调用统一走 infra.llm，模板渲染统一走 prompt.renderer（LangChain PromptTemplate）。
"""

import logging
from pathlib import Path

from dataocean.infra.llm import call_llm
from dataocean.infra.parsers import JsonBlockOutputParser
from dataocean.prompt.renderer import render_template_file

from .schema import (
    BatchGenerateResponse,
    DomainDoc,
    DomainGroup,
    GenerateDraftRequest,
    GenerateDraftResponse,
)

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).parent / "prompts"
_SKILLS_MD_TEMPLATE = PROMPTS_DIR / "skills_md_template.j2"
_DOMAIN_ANALYSIS_TEMPLATE = PROMPTS_DIR / "domain_analysis.j2"

# skills.md 草稿生成的系统角色提示
_SYSTEM_PROMPT = "你是一个数据库文档专家，负责根据数据库元数据生成结构化的 skills.md 业务知识文档。"
_DOMAIN_ANALYSIS_PROMPT = "你是一个数据库架构分析专家。请严格按照要求输出 JSON 格式。"

_MAX_WARNINGS = 50
_json_parser = JsonBlockOutputParser(allow_null=False)


def _check_missing_comments(tables: list) -> list[str]:
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


async def analyze_and_generate(request: GenerateDraftRequest) -> BatchGenerateResponse:
    """AI 自动分析业务域并批量生成 skills.md

    流程：
    1. 用 LLM 分析表结构，识别业务域分组
    2. 对每个域，用 LLM 生成独立的 skills.md
    3. 返回所有生成的文档

    Args:
        request: 包含快照元数据的请求对象

    Returns:
        批量生成的文档列表
    """
    logger.info(
        "开始域分析+批量生成 snapshot_id=%d datasource_id=%d tables=%d",
        request.snapshot_id,
        request.datasource_id,
        len(request.tables_metadata),
    )

    # Step 1: 域分析
    domains = await _analyze_domains(request)
    logger.info("域分析完成，识别出 %d 个业务域", len(domains))

    # Step 2: 逐域生成 skills.md
    docs = []
    for domain in domains:
        logger.info("生成域文档: %s (表: %s)", domain.domain_name, ", ".join(domain.table_names))
        doc = await _generate_domain_doc(request, domain)
        docs.append(doc)

    logger.info("批量生成完成，共 %d 份文档", len(docs))

    return BatchGenerateResponse(docs=docs, total_domains=len(docs))


async def _analyze_domains(request: GenerateDraftRequest) -> list[DomainGroup]:
    """用 LLM 分析表结构，识别业务域分组"""
    prompt = render_template_file(
        _DOMAIN_ANALYSIS_TEMPLATE,
        tables=request.tables_metadata,
        foreign_keys=request.foreign_keys,
    )

    response_text = await call_llm(
        system_prompt=_DOMAIN_ANALYSIS_PROMPT,
        user_prompt=prompt,
    )

    result = _json_parser.parse(response_text)
    domains_data = result.get("domains", [])

    if not domains_data:
        # 降级：所有表归为一个域
        logger.warning("域分析返回空结果，降级为单域模式")
        return [DomainGroup(
            domain_name="全部数据表",
            table_names=[t.table_name for t in request.tables_metadata],
            reason="AI 域分析失败，合并为单个域",
        )]

    domains = [DomainGroup(**d) for d in domains_data]

    # 校验：确保所有表都被覆盖
    all_tables = {t.table_name for t in request.tables_metadata}
    covered_tables = {t for d in domains for t in d.table_names}
    missing = all_tables - covered_tables
    if missing:
        # 把遗漏的表追加到最后一个域
        logger.warning("域分析遗漏了 %d 张表，追加到最后一个域", len(missing))
        domains[-1].table_names.extend(missing)

    return domains


async def _generate_domain_doc(
    request: GenerateDraftRequest, domain: DomainGroup
) -> DomainDoc:
    """为单个业务域生成 skills.md"""
    # 过滤出该域的表元数据
    table_set = set(domain.table_names)
    domain_tables = [t for t in request.tables_metadata if t.table_name in table_set]
    domain_fks = [
        fk for fk in request.foreign_keys
        if fk.get("source_table") in table_set or fk.get("target_table") in table_set
    ]

    # 渲染 Prompt 并调用 LLM
    prompt = render_template_file(
        _SKILLS_MD_TEMPLATE,
        tables=domain_tables,
        foreign_keys=domain_fks,
        indexes=request.indexes,
    )

    content = await call_llm(system_prompt=_SYSTEM_PROMPT, user_prompt=prompt)

    # 检查无注释字段
    warnings = _check_missing_comments(domain_tables)

    return DomainDoc(
        title=f"{domain.domain_name} skills.md",
        content=content,
        table_names=domain.table_names,
        warnings=warnings,
    )
