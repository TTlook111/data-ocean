"""RAG 分块策略

将元数据和 skills.md 内容切分为适合向量化的 chunks。
新策略：按三级标题细粒度切分，每张表/每个指标/每对关联独立 chunk，
提升向量检索精度。
"""

import logging
import re

from .schema import ChunkItem

logger = logging.getLogger(__name__)

# 从 chunk 文本中提取表名的正则（匹配常见格式）
_TABLE_NAME_PATTERNS = [
    re.compile(r"^###\s+(\w+)\s*[—\-↔]", re.MULTILINE),  # ### table_name — ...
    re.compile(r"^###\s+(\w+)\.(\w+)", re.MULTILINE),  # ### table.column
    re.compile(r"表:\s*(\w+)", re.MULTILINE),  # 表: table_name
    re.compile(r"适用表:\s*(\w+)", re.MULTILINE),  # 适用表: table_name
    re.compile(r"涉及表:\s*(.+)", re.MULTILINE),  # 涉及表: t1, t2
]

# 不需要向量化的章节标题关键词
_SKIP_SECTIONS = {"文档来源"}


def chunk_tables(tables_metadata: list[dict]) -> list[ChunkItem]:
    """表级分块：每张表生成一个 chunk

    chunk_text 格式：表名 + 注释 + 字段列表摘要
    """
    chunks = []
    for table in tables_metadata:
        table_name = table.get("table_name", "")
        table_comment = table.get("table_comment", "")
        columns = table.get("columns", [])

        # 组装 chunk 文本
        col_texts = []
        for col in columns:
            col_name = col.get("column_name", "")
            col_type = col.get("column_type", "")
            col_comment = col.get("column_comment", "")
            col_texts.append(
                f"{col_name}({col_type}): {col_comment}" if col_comment else f"{col_name}({col_type})"
            )

        chunk_text = f"表: {table_name}"
        if table_comment:
            chunk_text += f" — {table_comment}"
        chunk_text += "\n字段: " + ", ".join(col_texts[:20])  # 限制长度
        if len(col_texts) > 20:
            chunk_text += f" ... 共{len(col_texts)}个字段"

        chunks.append(
            ChunkItem(
                chunk_type="TABLE_DESC",
                chunk_text=chunk_text,
                related_table=table_name,
                governance_status=table.get("governance_status", "NORMAL"),
                review_status="APPROVED",
            )
        )

    return chunks


def chunk_skills_md(content: str) -> list[ChunkItem]:
    """skills.md 细粒度分块

    新策略：
    1. 先按 ## 切分为章节
    2. 跳过"文档来源"章节（无检索价值）
    3. 对每个章节，按 ### 进一步切分为独立 chunk
    4. 如果章节内没有 ### 子标题，整体作为一个 chunk
    5. 从 chunk 内容中提取 related_table
    """
    if not content:
        return []

    # 按二级标题切分
    sections = content.split("\n## ")
    chunks = []

    for i, section in enumerate(sections):
        text = section.strip()
        if not text:
            continue
        if i > 0:
            text = "## " + text

        # 获取章节标题（第一行）
        first_line = text.split("\n", 1)[0]

        # 跳过无检索价值的章节
        if any(skip in first_line for skip in _SKIP_SECTIONS):
            continue

        # 推断该章节的 chunk_type
        section_chunk_type = _infer_chunk_type(first_line)

        # 尝试按 ### 进一步切分
        sub_sections = _split_by_h3(text)

        if len(sub_sections) <= 1:
            # 没有 ### 子标题，整体作为一个 chunk
            related_table = _extract_table_name(text)
            chunks.append(
                ChunkItem(
                    chunk_type=section_chunk_type,
                    chunk_text=text[:8000],
                    related_table=related_table,
                )
            )
        else:
            # 按 ### 子标题逐个切分
            for sub in sub_sections:
                sub_text = sub.strip()
                if not sub_text or len(sub_text) < 10:
                    continue
                related_table = _extract_table_name(sub_text)
                # 子 chunk 继承父章节的 chunk_type
                chunks.append(
                    ChunkItem(
                        chunk_type=section_chunk_type,
                        chunk_text=sub_text[:8000],
                        related_table=related_table,
                    )
                )

    logger.info("skills.md 分块完成，共 %d 个 chunk", len(chunks))
    return chunks


def _split_by_h3(text: str) -> list[str]:
    """按三级标题 ### 切分文本

    返回切分后的段落列表。如果文本中没有 ### 标题，返回包含原文的单元素列表。
    第一段（## 标题行本身）如果内容很短则跳过。
    """
    parts = re.split(r"\n(?=### )", text)
    if len(parts) <= 1:
        return parts

    result = []
    for part in parts:
        stripped = part.strip()
        # 跳过只有 ## 标题行没有实质内容的段落
        if stripped.startswith("## ") and "\n" not in stripped:
            continue
        # 跳过只有 ## 标题 + 一行说明的段落（章节引导语）
        if stripped.startswith("## ") and stripped.count("\n") <= 1:
            continue
        if stripped:
            result.append(stripped)

    return result if result else [text]


def _extract_table_name(text: str) -> str:
    """从 chunk 文本中提取关联的表名

    按优先级尝试多种模式匹配，返回第一个匹配到的表名。
    """
    for pattern in _TABLE_NAME_PATTERNS:
        match = pattern.search(text)
        if match:
            # 对"涉及表"模式，取第一个表名
            name = match.group(1).strip()
            if "," in name:
                name = name.split(",")[0].strip()
            if name and len(name) <= 64:
                return name
    return ""


def _infer_chunk_type(text: str) -> str:
    """根据内容推断切片类型"""
    lower = text.lower()
    if "join" in lower or "关联" in lower or "↔" in text:
        return "JOIN_PATH"
    elif "指标" in lower or "metric" in lower or "sql 表达式" in lower:
        return "METRIC"
    elif "防坑" in lower or "注意" in lower or "误用" in lower:
        return "FIELD_NOTE"
    elif "场景" in lower or "骨架" in lower:
        return "QUERY_SCENE"
    return "TABLE_DESC"
