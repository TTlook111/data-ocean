"""提取治理资料的定长文本片段，并为每段保留真实文件位置。"""

import base64
import csv
import io
import re
import zipfile
from collections.abc import Iterator
from itertools import islice
from xml.etree import ElementTree

from pypdf import PdfReader
from pypdf.errors import PdfReadError

MAX_MEMBER_BYTES = 1024 * 1024
MAX_PDF_PAGES = 16
WORD = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
SHEET = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def _xml(archive: zipfile.ZipFile, member: str) -> ElementTree.Element:
    """限制 OpenXML 单成员展开量，拒绝文档声明及自定义实体。"""
    with archive.open(member) as contents:
        # 读取前先按 ZIP 声明大小拒绝过大内容。
        if archive.getinfo(member).file_size > MAX_MEMBER_BYTES:
            raise ValueError("OpenXML member exceeds limit")
        raw = contents.read(MAX_MEMBER_BYTES + 1)
    if len(raw) > MAX_MEMBER_BYTES or b"<!DOCTYPE" in raw.replace(b"\x00", b"").upper():
        # 禁止解析受控文本以外的 XML 结构。
        raise ValueError("OpenXML member is unavailable")
    return ElementTree.fromstring(raw)


def _openxml(data: bytes, suffix: str) -> Iterator[tuple[str, str]]:
    """按 Word 段落或 Excel 工作表和单元格回传实有文本。"""
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        # 格式解析只访问主文档和内部普通工作表，不读取宏或外部链接。
        if suffix == "docx":
            for paragraph, node in enumerate(
                _xml(archive, "word/document.xml").iter(WORD + "p"), 1
            ):
                # 按主文档顺序计数，表内段落沿用同一序号。
                yield (
                    f"DOCX_PARAGRAPH:{paragraph}",
                    "".join(text.text or "" for text in node.iter(WORD + "t")),
                )
            return

        names = archive.namelist()
        shared = []
        if "xl/sharedStrings.xml" in names:
            # 字符串表使用单元格实际引用的索引，不当成整份证据。
            shared = [
                "".join(node.itertext())
                for node in _xml(archive, "xl/sharedStrings.xml").findall(SHEET + "si")
            ]
        for name in names:
            # 以实际工作表部件编号定位，兼容文件内的非连续编号。
            sheet = re.fullmatch(r"xl/worksheets/sheet([1-9]\d{0,5})\.xml", name)
            if not sheet:
                continue
            for cell in _xml(archive, name).iter(SHEET + "c"):
                # 不读取公式或公式缓存，只复用现有文本和值。
                coordinate = cell.get("r", "")
                if (
                    not re.fullmatch(r"[A-Z]{1,3}[1-9]\d{0,6}", coordinate)
                    or cell.find(SHEET + "f") is not None
                ):
                    continue
                value = cell.findtext(SHEET + "v", "")
                if cell.get("t") == "s":
                    # 缺少合法索引时跳过该单元格。
                    value = (
                        shared[int(value)] if value.isdecimal() and int(value) < len(shared) else ""
                    )
                elif cell.get("t") == "inlineStr":
                    # 内联富文本合并到一个单元格证据。
                    value = "".join(node.text or "" for node in cell.iter(SHEET + "t"))
                yield f"XLSX_CELL:{sheet[1]}:{coordinate}", value


def fragments(name: str, encoded: str) -> Iterator[tuple[str, str]]:
    """定位附件片段。破损、加密及无文本原件由调用方登记为不可用。"""
    try:
        # Java 已按 MIME 类型与原件大小限额发送，Python 再检查解析格式。
        suffix = name.rsplit(".", 1)[-1].casefold()
        data = base64.b64decode(encoded, validate=True)
        if suffix == "pdf":
            # 只提取嵌入文本，不运行 PDF 动作，拒绝加密并限制前部页数。
            with PdfReader(
                io.BytesIO(data), strict=True, xref_stream_maximum_entries=50_000
            ) as reader:
                if reader.is_encrypted:
                    return
                for page, node in enumerate(islice(reader.pages, MAX_PDF_PAGES), 1):
                    yield f"PDF_PAGE:{page}", node.extract_text()
        elif suffix in {"docx", "xlsx"}:
            yield from _openxml(data, suffix)
        elif suffix in {"txt", "md", "csv"}:
            # 以文件物理行号定位，CSV 中引号包裹的换行仍属于同一记录。
            content = data.decode("utf-8-sig")
            if suffix == "csv":
                records = csv.reader(io.StringIO(content, newline=""), strict=True)
                first = 1
                for record in records:
                    yield f"CSV_LINES:{first}-{records.line_num}", " | ".join(record)
                    first = records.line_num + 1
            else:
                for line, value in enumerate(content.splitlines(), 1):
                    yield f"TEXT_LINE:{line}", value
    except (
        ValueError,
        KeyError,
        OSError,
        RuntimeError,
        PdfReadError,
        zipfile.BadZipFile,
        ElementTree.ParseError,
        csv.Error,
    ):
        # 忽略当前不可解析的部分，其他来源继续参与生成。
        return
