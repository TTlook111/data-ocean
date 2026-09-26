"""RAG chunking strategy backed by LangChain text splitters.

Python owns RAG-specific splitting. Java sends the complete skills.md document,
stores the returned chunk snapshot for governance/rebuild visibility, and never
implements its own chunking rules.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re

from langchain_text_splitters import (
    MarkdownHeaderTextSplitter,
    RecursiveCharacterTextSplitter,
)

from .schema import ChunkItem

logger = logging.getLogger(__name__)

# Chunk 长度使用 token 计算，而不是字符数。900 是目标值，给上下文前缀
# 和不同语言的分词差异预留余量；最终长 chunk 不应超过 1000 token。
TARGET_CHUNK_TOKENS = 900
MAX_CHUNK_TOKENS = 1000
CHUNK_OVERLAP_TOKENS = 150
# 只过滤真正为空的语义单元；短字段说明、短 JOIN 条件也必须保留。
MIN_CHUNK_TEXT_LENGTH = 1

_REQUIRED_SECTION_KEYWORDS = (
    ("文档来源", "document source"),
    ("核心表", "core table"),
    ("join path", "关联路径"),
    ("指标", "metric"),
    ("字段防坑", "field note"),
    ("查询场景", "query scene", "scenario"),
)

_MARKDOWN_SPLITTER = MarkdownHeaderTextSplitter(
    headers_to_split_on=[
        ("##", "section"),
        ("###", "heading"),
        ("####", "subheading"),
        ("#####", "detail"),
    ],
    strip_headers=False,
)

try:
    import tiktoken
except ImportError:  # pragma: no cover - 仅在精简运行环境中触发
    tiktoken = None


_TOKEN_ENCODER = None
if tiktoken is not None:
    try:
        # Qwen 的 tokenizer 不随 Python 服务发布；cl100k_base 作为稳定的
        # 本地 token 预算器，实际 embedding 请求仍由 Qwen API 完成。
        _TOKEN_ENCODER = tiktoken.get_encoding("cl100k_base")
    except Exception:  # pragma: no cover - 编码器初始化失败时使用估算
        _TOKEN_ENCODER = None


def count_tokens(text: str) -> int:
    """计算切分预算 token 数。

    优先使用本地 tiktoken 编码器；极简环境没有 tiktoken 时，使用按中英文
    字符/单词拆分的保守估算。该函数只用于切分预算，不改变 Embedding API。
    """
    if not text:
        return 0
    if _TOKEN_ENCODER is not None:
        return len(_TOKEN_ENCODER.encode(text, disallowed_special=()))
    return len(re.findall(r"[\u4e00-\u9fff]|[A-Za-z0-9_]+|[^\w\s]", text))


def _build_long_chunk_splitter(chunk_size: int) -> RecursiveCharacterTextSplitter:
    """构建按 token 长度工作的递归切分器。"""
    overlap = min(CHUNK_OVERLAP_TOKENS, max(1, chunk_size // 4))
    return RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=overlap,
        length_function=count_tokens,
        separators=["\n\n", "\n", "。", "！", "？", ".", "!", "?", " ", ""],
    )

_SKIP_SECTION_KEYWORDS = (
    "document source",
    "\u6587\u6863\u6765\u6e90",
)

_TABLE_NAME_PATTERNS = (
    re.compile(r"\u9002\u7528\u8868\s*[:\uff1a]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"\u6d89\u53ca\u8868\s*[:\uff1a]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"\u8868\u540d\s*[:\uff1a]?\s*`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\s*[\u2014\-]", re.MULTILINE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\s*[\u2194\u2192]", re.MULTILINE),
    re.compile(r"^###\s+`?([a-zA-Z_][\w\-]*)`?\.`?([a-zA-Z_][\w\-]*)`?", re.MULTILINE),
)


def chunk_tables(tables_metadata: list[dict]) -> list[ChunkItem]:
    """Build token-bounded TABLE_DESC chunks for schema-only fallback usage."""
    chunks: list[ChunkItem] = []
    for table in tables_metadata:
        table_name = table.get("table_name", "")
        table_comment = table.get("table_comment", "")
        columns = table.get("columns", [])

        column_texts = []
        for col in columns:
            col_name = col.get("column_name", "")
            col_type = col.get("column_type", "")
            col_comment = col.get("column_comment", "")
            column_texts.append(
                f"{col_name}({col_type}): {col_comment}" if col_comment else f"{col_name}({col_type})"
            )

        chunk_text = f"\u8868 {table_name}"
        if table_comment:
            chunk_text += f" - {table_comment}"
        chunk_text += "\n\u5b57\u6bb5: " + ", ".join(column_texts)

        related_columns = [
            f"{table_name}.{col.get('column_name')}" if table_name else col.get("column_name", "")
            for col in columns
            if col.get("column_name")
        ]
        group_id = _make_chunk_group_id("TABLE_DESC", table_name, table_name, chunk_text)
        for part in _split_long_chunk(chunk_text, TARGET_CHUNK_TOKENS):
            normalized = part.strip()
            if not normalized:
                continue
            chunks.append(
                ChunkItem(
                    chunk_type="TABLE_DESC",
                    chunk_text=normalized,
                    related_table=table_name,
                    related_tables=[table_name] if table_name else [],
                    related_columns=related_columns,
                    chunk_index=len(chunks),
                    chunk_group_id=group_id,
                    content_hash=_content_hash(normalized),
                    governance_status=table.get("governance_status", "NORMAL"),
                    review_status="APPROVED",
                )
            )

    return chunks


def validate_skills_md_structure(content: str, expected_snapshot_id: int | None = None) -> list[str]:
    """校验待发布 skills.md 的结构，不判断业务事实真假。

    业务事实仍由 Java 的元数据、审核流程和发布前治理校验负责；这里仅
    防止 Markdown 结构损坏后进入切分和向量化流程。
    """
    if not content or not content.strip():
        return ["skills.md 内容为空"]

    errors: list[str] = []
    headings = re.findall(r"^(##{1,2})\s+(.+?)\s*$", content, re.MULTILINE)
    top_level_titles = [title.lower() for level, title in headings if level == "##"]
    h3_titles = [title.strip() for level, title in headings if level == "###"]

    for keywords in _REQUIRED_SECTION_KEYWORDS:
        if not any(any(keyword in title for keyword in keywords) for title in top_level_titles):
            errors.append(f"缺少顶级章节：{'/'.join(keywords[:2])}")

    if not h3_titles:
        errors.append("至少需要一个 ### 语义小节")
    elif any(not title.strip() for title in h3_titles):
        errors.append("存在空的 ### 标题")

    if content.count("```") % 2 != 0:
        errors.append("Markdown 代码块未闭合")
    if "{{" in content or "}}" in content:
        errors.append("文档仍包含未渲染的模板占位符")

    table_ids: list[int] = []
    column_ids: list[int] = []
    snapshot_ids: set[int] = set()
    markers = list(_FACT_MARKER_PATTERN.finditer(content))
    if not markers:
        errors.append("缺少带来源快照和完整资源依赖的 fact marker")
    for match in markers:
        try:
            marker = json.loads(match.group(1))
        except (TypeError, ValueError):
            errors.append("fact marker JSON 无效")
            continue
        snapshot_id = marker.get("snapshotId")
        fact_type = marker.get("factType")
        source_ids = marker.get("sourceIds")
        dependencies = marker.get("dependencies")
        if not isinstance(snapshot_id, int) or snapshot_id <= 0:
            errors.append("fact marker 缺少有效 snapshotId")
        else:
            snapshot_ids.add(snapshot_id)
            if expected_snapshot_id is not None and snapshot_id != expected_snapshot_id:
                errors.append("fact marker 与请求的 snapshotId 不一致")
        if not isinstance(source_ids, list) or not source_ids:
            errors.append("fact marker 缺少来源 ID")
        if not isinstance(dependencies, list) or not dependencies:
            errors.append("fact marker 缺少资源依赖")
        if fact_type == "TABLE_STRUCTURE" and isinstance(source_ids, list) and source_ids:
            table_ids.append(int(source_ids[0]))
        elif fact_type == "COLUMN_STRUCTURE" and isinstance(source_ids, list) and len(source_ids) == 2:
            column_ids.append(int(source_ids[1]))
    if len(snapshot_ids) > 1:
        errors.append("文档 fact marker 混入多个 snapshotId")
    if not table_ids or len(table_ids) != len(set(table_ids)):
        errors.append("表结构事实缺失或重复")
    if not column_ids or len(column_ids) != len(set(column_ids)):
        errors.append("字段结构事实缺失或重复")

    return errors


def chunk_skills_md(content: str, expected_snapshot_id: int | None = None) -> list[ChunkItem]:
    """Split skills.md into retrieval-oriented chunks.

    LangChain handles Markdown header splitting and recursive long-text
    splitting. DataOcean keeps the domain mapping from header metadata to
    chunk_type and table/column metadata.

    实现 context-enriched chunking（参考 Anthropic Contextual Retrieval）：
    为每个 chunk 附加上下文前缀，帮助 embedding 模型理解 chunk 在文档中的位置和含义。
    """
    if not content or not content.strip():
        return []

    chunks: list[ChunkItem] = []
    for document in _MARKDOWN_SPLITTER.split_text(content):
        section = str(document.metadata.get("section", ""))
        heading = str(document.metadata.get("heading", ""))
        if _should_skip_section(section or heading):
            continue
        marker, machine_text = _extract_fact_marker(document.page_content)
        if marker is None:
            logger.warning("skills.md chunk rejected because it has no source/dependency marker heading=%s", heading)
            continue
        if not marker.get("dependencies") or not marker.get("sourceIds"):
            logger.warning("skills.md chunk rejected because its source/dependency list is empty heading=%s", heading)
            continue
        snapshot_id = marker.get("snapshotId")
        if not isinstance(snapshot_id, int) or snapshot_id <= 0:
            logger.warning("skills.md chunk rejected because its snapshot binding is invalid heading=%s", heading)
            continue
        if expected_snapshot_id is not None and snapshot_id != expected_snapshot_id:
            logger.warning("skills.md chunk rejected because it belongs to another snapshot expected=%s actual=%s",
                           expected_snapshot_id, snapshot_id)
            continue
        fact_type = str(marker.get("factType") or "")
        chunk_type = _fact_chunk_type(fact_type)
        if chunk_type is None:
            logger.warning("skills.md chunk rejected because fact type is unsupported type=%s", fact_type)
            continue
        resource_dependencies = list(dict.fromkeys(
            str(item) for item in marker.get("dependencies", []) if str(item).strip()
        ))
        table_names = list(dict.fromkeys(
            item.split(":", 1)[1] for item in resource_dependencies if item.startswith("table:")
        ))
        column_names = list(dict.fromkeys(
            item.split(":", 1)[1] for item in resource_dependencies if item.startswith("column:")
        ))
        table_name = table_names[0] if table_names else ""
        source_ids = [str(item) for item in marker.get("sourceIds", [])]
        fact_review_status = str(marker.get("reviewStatus") or "PENDING").upper()
        if fact_review_status not in {"APPROVED", "PENDING", "REJECTED"}:
            logger.warning("skills.md chunk rejected because review status is invalid status=%s", fact_review_status)
            continue
        governance_status = str(marker.get("governanceStatus") or "NORMAL").upper()

        # 生成上下文前缀（参考 Anthropic Contextual Retrieval）
        context_prefix = _build_context_prefix(chunk_type, section, heading, table_name)
        prefix_tokens = count_tokens(context_prefix)
        # 前缀是合成的辅助信息，不能挤占正文到超过最大预算；极端长标题
        # 直接省略前缀，正文本身仍保留原始 Markdown 标题和内容。
        if prefix_tokens >= MAX_CHUNK_TOKENS:
            context_prefix = ""
            prefix_tokens = 0
        content_budget = max(
            1,
            min(
                TARGET_CHUNK_TOKENS - prefix_tokens,
                MAX_CHUNK_TOKENS - prefix_tokens,
            ),
        )
        chunk_group_id = _make_chunk_group_id(
            chunk_type,
            section,
            heading,
            "|".join([fact_type, *source_ids]),
        )

        for text in _split_long_chunk(machine_text, content_budget):
            normalized = text.strip()
            if len(normalized) < MIN_CHUNK_TEXT_LENGTH:
                continue

            # 将上下文前缀附加到 chunk 文本前面
            enriched_text = context_prefix + normalized if context_prefix else normalized

            chunk_index = len(chunks)
            chunks.append(
                ChunkItem(
                    chunk_type=chunk_type,
                    chunk_text=enriched_text,
                    related_table=table_name,
                    related_column=_unqualified_column_name(column_names[0]) if column_names else "",
                    related_tables=table_names,
                    related_columns=column_names,
                    chunk_index=chunk_index,
                    chunk_group_id=chunk_group_id,
                    content_hash=_content_hash(enriched_text),
                    governance_status=governance_status,
                    review_status=fact_review_status,
                    source_snapshot_id=snapshot_id,
                    resource_dependencies=resource_dependencies,
                    fact_source_ids=source_ids,
                    fact_type=fact_type,
                    fact_review_status=fact_review_status,
                )
            )

    logger.info("skills.md chunking complete chunks=%d splitter=langchain", len(chunks))
    return chunks


_FACT_MARKER_PATTERN = re.compile(r"<!--\s*dataocean-fact:\s*(\{.*?\})\s*-->", re.DOTALL)


def _extract_fact_marker(text: str) -> tuple[dict | None, str]:
    markers = list(_FACT_MARKER_PATTERN.finditer(text))
    if len(markers) != 1:
        return None, ""
    try:
        marker = json.loads(markers[0].group(1))
    except (TypeError, ValueError):
        return None, ""
    machine_text = _FACT_MARKER_PATTERN.sub("", text).strip()
    return (marker if isinstance(marker, dict) else None), machine_text


def _fact_chunk_type(fact_type: str) -> str | None:
    return {
        "TABLE_STRUCTURE": "TABLE_DESC",
        "TABLE_COMMENT": "TABLE_DESC",
        "COLUMN_STRUCTURE": "FIELD_NOTE",
        "COLUMN_COMMENT": "FIELD_NOTE",
        "JOIN_PATH": "JOIN_PATH",
        "JOIN_CANDIDATE": "JOIN_CANDIDATE",
        "LINEAGE": "LINEAGE",
        "DERIVED_FROM": "LINEAGE",
    }.get(fact_type)


def _split_long_chunk(text: str, content_budget: int = TARGET_CHUNK_TOKENS) -> list[str]:
    """拆分超长 chunk

    使用 RecursiveCharacterTextSplitter 按段落、句子等边界拆分，
    避免单个 chunk 过大影响检索精度。
    """
    if count_tokens(text) <= content_budget:
        return [text]
    return _build_long_chunk_splitter(content_budget).split_text(text)


def _should_skip_section(title: str) -> bool:
    """判断是否跳过该章节

    跳过"文档来源"等非业务内容章节。
    """
    lowered = title.lower()
    return any(keyword.lower() in lowered for keyword in _SKIP_SECTION_KEYWORDS)


def _extract_table_name(text: str, heading: str = "") -> str:
    """从文本中提取表名

    使用预编译的正则模式匹配表名，优先从标题中提取，
    标题未匹配则从正文中提取。
    """
    names = _extract_table_names(text, heading)
    return names[0] if names else ""


def _extract_table_names(text: str, heading: str = "") -> list[str]:
    """提取一个语义单元中涉及的全部表名，保持出现顺序并去重。"""
    names: list[str] = []

    def add(value: str | None) -> None:
        if not value:
            return
        name = value.strip().strip("`")
        if name and len(name) <= 64 and name not in names:
            names.append(name)

    candidates = (heading, text)
    for candidate in candidates:
        for pattern in _TABLE_NAME_PATTERNS:
            for match in pattern.finditer(candidate):
                add(match.group(1))

        # Join Path 标题或正文中的 A ↔ B / A -> B 关系。
        for match in re.finditer(
            r"`?([a-zA-Z_][\w\-]*)`?\s*(?:↔|→|<-|->)\s*`?([a-zA-Z_][\w\-]*)`?",
            candidate,
        ):
            add(match.group(1))
            add(match.group(2))

        # SQL 条件中的 table.column 形式。
        for match in re.finditer(r"`?([a-zA-Z_][\w\-]*)`?\s*\.\s*`?[a-zA-Z_][\w\-]*`?", candidate):
            add(match.group(1))

    return names


def _extract_column_name(text: str, heading: str = "") -> str:
    """从文本中提取列名

    优先从标题中提取（### table.column 格式），
    标题未匹配则从正文中提取（"字段 xxx"、"列 xxx" 等模式）。
    """
    columns = _extract_column_names(text, heading)
    return columns[0] if columns else ""


def _extract_column_names(text: str, heading: str = "") -> list[str]:
    """提取语义单元中的字段名，兼容 table.column 和自然语言字段描述。"""
    names: list[str] = []

    def add(value: str | None) -> None:
        if not value:
            return
        name = value.strip().strip("`")
        if name and len(name) <= 64 and name not in names:
            names.append(name)

    for candidate in (heading, text):
        for match in re.finditer(
            r"`?([a-zA-Z_][\w\-]*)`?\s*\.\s*`?([a-zA-Z_][\w\-]*)`?", candidate
        ):
            add(f"{match.group(1)}.{match.group(2)}")

        for pattern in (
            re.compile(r"字段\s+`?([a-zA-Z_][\w\-]*)`?"),
            re.compile(r"列\s+`?([a-zA-Z_][\w\-]*)`?"),
            re.compile(r"column\s+`?([a-zA-Z_][\w\-]*)`?", re.IGNORECASE),
        ):
            for match in pattern.finditer(candidate):
                add(match.group(1))

    return names


def _unqualified_column_name(column_name: str) -> str:
    """保留 related_columns 的限定名，但为兼容旧 prompt 提供列名单数值。"""
    return column_name.rsplit(".", 1)[-1]


def _make_chunk_group_id(chunk_type: str, section: str, heading: str, text: str) -> str:
    """生成不依赖数据库自增 ID 的语义小节分组标识。"""
    raw = "\n".join((chunk_type, section.strip(), heading.strip(), text.strip()))
    return f"group-{hashlib.sha1(raw.encode('utf-8')).hexdigest()[:20]}"


def _content_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _build_context_prefix(chunk_type: str, section: str, heading: str, table_name: str) -> str:
    """为 chunk 生成上下文前缀（参考 Anthropic Contextual Retrieval）

    在每个 chunk 前附加 1-2 句上下文说明，解释该 chunk 在文档中的位置和含义。
    这可以显著提升 Milvus 检索的精确度，尤其对 FIELD_NOTE 和 QUERY_SCENE 类型。

    Args:
        chunk_type: chunk 类型
        section: 所属章节
        heading: 所属标题
        table_name: 关联表名

    Returns:
        上下文前缀字符串
    """
    parts = []

    # 根据 chunk 类型生成不同的上下文描述
    type_descriptions = {
        "JOIN_PATH": "这是表关联路径定义",
        "METRIC": "这是业务指标计算口径",
        "FIELD_NOTE": "这是字段使用注意事项",
        "QUERY_SCENE": "这是查询场景示例",
        "TABLE_DESC": "这是表结构描述",
    }
    type_desc = type_descriptions.get(chunk_type, "")
    if type_desc:
        parts.append(type_desc)

    # 附加表名信息
    if table_name:
        parts.append(f"涉及表 {table_name}")

    # 附加章节信息
    if heading:
        parts.append(f"来自「{heading}」")

    if not parts:
        return ""

    return "【" + "，".join(parts) + "】\n"


def _infer_chunk_type(section: str, heading: str, text: str) -> str:
    """\u6839\u636e\u6807\u9898\u548c\u6b63\u6587\u63a8\u65ad chunk \u7c7b\u578b

    \u4f18\u5148\u7ea7\uff1aheader \u5173\u952e\u8bcd > body \u5173\u952e\u8bcd\uff08\u4ec5 header \u672a\u547d\u4e2d\u65f6\uff09\u3002
    body \u5173\u952e\u8bcd\u4f7f\u7528\u66f4\u7cbe\u786e\u7684\u6a21\u5f0f\uff0c\u907f\u514d"join"\u7b49\u901a\u7528\u8bcd\u8bef\u5206\u7c7b\u3002
    """
    header_text = f"{section}\n{heading}".lower()
    body_text = text.lower()

    # \u7b2c\u4e00\u4f18\u5148\u7ea7\uff1aheader \u5173\u952e\u8bcd\u5339\u914d\uff08\u7cbe\u786e\u5ea6\u9ad8\uff09
    if any(keyword in header_text for keyword in ("join", "join path", "\u5173\u8054", "\u2194", "\u2192")):
        return "JOIN_PATH"
    if any(keyword in header_text for keyword in ("metric", "\u6307\u6807", "\u53e3\u5f84")):
        return "METRIC"
    if any(keyword in header_text for keyword in ("field note", "\u9632\u5751", "\u6ce8\u610f", "\u8bef\u7528")):
        return "FIELD_NOTE"
    if any(keyword in header_text for keyword in ("scenario", "\u573a\u666f", "\u67e5\u8be2\u573a\u666f", "\u9aa8\u67b6")):
        return "QUERY_SCENE"

    # \u7b2c\u4e8c\u4f18\u5148\u7ea7\uff1abody \u5173\u952e\u8bcd\u5339\u914d\uff08\u4ec5 header \u672a\u547d\u4e2d\u65f6\uff0c\u4f7f\u7528\u7cbe\u786e\u6a21\u5f0f\uff09
    # \u4f7f\u7528\u66f4\u5177\u4f53\u7684\u77ed\u8bed\u800c\u975e\u5355\u8bcd\uff0c\u907f\u514d "join" \u5728 METRIC \u63cf\u8ff0\u4e2d\u88ab\u8bef\u5224
    if any(keyword in body_text for keyword in ("\u5173\u8054\u6761\u4ef6", "join path", "\u2194", "\u2192")):
        return "JOIN_PATH"
    if any(keyword in body_text for keyword in ("sql \u8868\u8fbe\u5f0f", "\u6307\u6807\u540d\u79f0", "\u805a\u5408\u53e3\u5f84")):
        return "METRIC"

    return "TABLE_DESC"
