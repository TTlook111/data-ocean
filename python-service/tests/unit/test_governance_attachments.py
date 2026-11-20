"""核对正常附件的确切位置和不可解析附件的降级行为。"""

import base64
import io
from zipfile import ZipFile

from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from dataocean_ai.governance.attachments import fragments


def _encoded(data: bytes) -> str:
    """按工作者收到的附件格式编码测试文件。"""
    return base64.b64encode(data).decode()


def _archive(name: str, text: str) -> str:
    """构造只含实际文档成员的 OpenXML 测试件。"""
    buffer = io.BytesIO()
    with ZipFile(buffer, mode="w") as archive:
        # 只提供附件提取器按格式约定读取的成员。
        archive.writestr(name, text)
    return _encoded(buffer.getvalue())


def _pdf(text: str | None, encrypted: bool = False) -> str:
    """构造有文本、无文本或加密的真实 PDF。"""
    writer = PdfWriter()
    page = writer.add_blank_page(width=150, height=100)
    if text:
        # 注册标准内置字体和实际 PDF 文字指令。
        font = DictionaryObject(
            {
                NameObject("/Type"): NameObject("/Font"),
                NameObject("/Subtype"): NameObject("/Type1"),
                NameObject("/BaseFont"): NameObject("/Helvetica"),
            }
        )
        page[NameObject("/Resources")] = DictionaryObject(
            {
                NameObject("/Font"): DictionaryObject({NameObject("/F1"): font}),
            }
        )
        stream = DecodedStreamObject()
        stream.set_data(f"BT /F1 12 Tf 10 20 Td ({text}) Tj ET".encode())
        page[NameObject("/Contents")] = stream
    if encrypted:
        # 加密附件不接受猜测或外发。
        writer.encrypt("synthetic")
    buffer = io.BytesIO()
    writer.write(buffer)
    writer.close()
    return _encoded(buffer.getvalue())


def test_preserves_text_and_office_locations():
    """固定 TXT、Markdown、跨行 CSV 和两种 Office 文档的实际位置。"""
    assert list(fragments("a.txt", _encoded(b"one\nsecond")))[1] == ("TEXT_LINE:2", "second")
    assert list(fragments("a.md", _encoded(b"# Rule"))) == [("TEXT_LINE:1", "# Rule")]
    csv = _encoded(b'name,value\r\n"two\r\nlines",7')
    assert list(fragments("a.csv", csv))[1] == ("CSV_LINES:2-3", "two\r\nlines | 7")
    word = _archive(
        "word/document.xml",
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        "<w:p><w:r><w:t>Rule</w:t></w:r></w:p></w:document>",
    )
    assert list(fragments("a.docx", word)) == [("DOCX_PARAGRAPH:1", "Rule")]
    sheet = _archive(
        "xl/worksheets/sheet3.xml",
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<c r="B2" t="inlineStr"><is><t>Sales</t></is></c>'
        '<c r="C2"><f>2+3</f><v>5</v></c></worksheet>',
    )
    assert list(fragments("a.xlsx", sheet)) == [("XLSX_CELL:3:B2", "Sales")]


def test_pdf_location_and_unavailable_inputs():
    """PDF 文本按页定位，加密、无文本、损坏和超限成员不作为依据。"""
    assert list(fragments("a.pdf", _pdf("Test rule"))) == [("PDF_PAGE:1", "Test rule")]
    assert list(fragments("a.pdf", _pdf("Private", encrypted=True))) == []
    assert list(fragments("a.pdf", _pdf(None))) == [("PDF_PAGE:1", "")]
    assert list(fragments("a.pdf", _encoded(b"%PDF-1.7 broken"))) == []
    assert list(fragments("a.docx", _archive("word/document.xml", "x" * 1048577))) == []
