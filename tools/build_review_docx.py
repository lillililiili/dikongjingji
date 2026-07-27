from __future__ import annotations

from pathlib import Path
from typing import Iterable, Sequence

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT.parent / "全国化低空与机器人综合服务平台_巡检目录I1_Codex模块化开发规范_V1.0.docx"
REF_1 = ROOT / "references" / "ui-01-inspection-entry.png"
REF_2 = ROOT / "references" / "ui-02-inspection-catalog.png"

FONT = "Noto Sans S Chinese"
MONO = "Consolas"
BLUE = "2E74B5"
DARK_BLUE = "1F4D78"
INK = "1B2430"
MUTED = "667085"
LIGHT = "E8EEF5"
LIGHTER = "F4F6F9"
ORANGE = "FF6A00"
ORANGE_LIGHT = "FFF3E8"
GREEN = "19B866"
RED = "B42318"
BORDER = "D8DEE7"
WHITE = "FFFFFF"

PAGE_DXA = 12240
CONTENT_DXA = 9360
TABLE_INDENT_DXA = 120


def rgb(hex_value: str) -> RGBColor:
    return RGBColor.from_string(hex_value)


def set_run_font(run, name: str = FONT, size: float | None = None,
                 color: str | None = None, bold: bool | None = None,
                 italic: bool | None = None) -> None:
    run.font.name = name
    rpr = run._element.get_or_add_rPr()
    rfonts = rpr.rFonts
    if rfonts is None:
        rfonts = OxmlElement("w:rFonts")
        rpr.insert(0, rfonts)
    rfonts.set(qn("w:ascii"), name)
    rfonts.set(qn("w:hAnsi"), name)
    rfonts.set(qn("w:eastAsia"), name)
    rfonts.set(qn("w:cs"), name)
    if size is not None:
        run.font.size = Pt(size)
    if color is not None:
        run.font.color.rgb = rgb(color)
    if bold is not None:
        run.bold = bold
    if italic is not None:
        run.italic = italic


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top: int = 80, start: int = 120,
                     bottom: int = 80, end: int = 120) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for tag, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{tag}"))
        if node is None:
            node = OxmlElement(f"w:{tag}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_cell_width(cell, width_dxa: int) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_w = tc_pr.find(qn("w:tcW"))
    if tc_w is None:
        tc_w = OxmlElement("w:tcW")
        tc_pr.append(tc_w)
    tc_w.set(qn("w:w"), str(width_dxa))
    tc_w.set(qn("w:type"), "dxa")


def set_repeat_table_header(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def set_table_geometry(table, widths_dxa: Sequence[int], indent_dxa: int = TABLE_INDENT_DXA) -> None:
    if sum(widths_dxa) != CONTENT_DXA:
        raise ValueError(f"table widths must sum to {CONTENT_DXA}: {widths_dxa}")
    table.autofit = False
    table.alignment = WD_TABLE_ALIGNMENT.LEFT
    tbl = table._tbl
    tbl_pr = tbl.tblPr

    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(CONTENT_DXA))
    tbl_w.set(qn("w:type"), "dxa")

    tbl_ind = tbl_pr.find(qn("w:tblInd"))
    if tbl_ind is None:
        tbl_ind = OxmlElement("w:tblInd")
        tbl_pr.append(tbl_ind)
    tbl_ind.set(qn("w:w"), str(indent_dxa))
    tbl_ind.set(qn("w:type"), "dxa")

    grid = tbl.tblGrid
    for child in list(grid):
        grid.remove(child)
    for width in widths_dxa:
        grid_col = OxmlElement("w:gridCol")
        grid_col.set(qn("w:w"), str(width))
        grid.append(grid_col)

    for row in table.rows:
        for idx, cell in enumerate(row.cells):
            set_cell_width(cell, widths_dxa[idx])
            set_cell_margins(cell)
            cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER


def set_paragraph_border(paragraph, color: str = ORANGE, size: int = 18,
                         side: str = "left", space: int = 8) -> None:
    p_pr = paragraph._p.get_or_add_pPr()
    p_bdr = p_pr.find(qn("w:pBdr"))
    if p_bdr is None:
        p_bdr = OxmlElement("w:pBdr")
        p_pr.append(p_bdr)
    border = OxmlElement(f"w:{side}")
    border.set(qn("w:val"), "single")
    border.set(qn("w:sz"), str(size))
    border.set(qn("w:space"), str(space))
    border.set(qn("w:color"), color)
    p_bdr.append(border)


def set_paragraph_shading(paragraph, fill: str) -> None:
    p_pr = paragraph._p.get_or_add_pPr()
    shd = p_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        p_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def add_page_number(paragraph) -> None:
    paragraph.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    run = paragraph.add_run()
    fld_char1 = OxmlElement("w:fldChar")
    fld_char1.set(qn("w:fldCharType"), "begin")
    instr_text = OxmlElement("w:instrText")
    instr_text.set(qn("xml:space"), "preserve")
    instr_text.text = " PAGE "
    fld_char2 = OxmlElement("w:fldChar")
    fld_char2.set(qn("w:fldCharType"), "end")
    run._r.append(fld_char1)
    run._r.append(instr_text)
    run._r.append(fld_char2)
    set_run_font(run, size=9, color=MUTED)


def set_image_alt(inline_shape, title: str, description: str) -> None:
    doc_pr = inline_shape._inline.docPr
    doc_pr.set("title", title)
    doc_pr.set("descr", description)


def configure_document(doc: Document) -> None:
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = Inches(1)
    section.bottom_margin = Inches(1)
    section.left_margin = Inches(1)
    section.right_margin = Inches(1)
    section.header_distance = Inches(0.492)
    section.footer_distance = Inches(0.492)
    section.different_first_page_header_footer = True

    styles = doc.styles

    normal = styles["Normal"]
    normal.font.name = FONT
    normal._element.rPr.rFonts.set(qn("w:ascii"), FONT)
    normal._element.rPr.rFonts.set(qn("w:hAnsi"), FONT)
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
    normal.font.size = Pt(11)
    normal.font.color.rgb = rgb(INK)
    normal.paragraph_format.space_before = Pt(0)
    normal.paragraph_format.space_after = Pt(6)
    normal.paragraph_format.line_spacing = 1.25

    title = styles["Title"]
    title.font.name = FONT
    title._element.rPr.rFonts.set(qn("w:ascii"), FONT)
    title._element.rPr.rFonts.set(qn("w:hAnsi"), FONT)
    title._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
    title.font.size = Pt(25)
    title.font.bold = True
    title.font.color.rgb = rgb(INK)
    title.paragraph_format.space_before = Pt(0)
    title.paragraph_format.space_after = Pt(8)

    for style_name, size, color, before, after in (
        ("Heading 1", 16, BLUE, 18, 10),
        ("Heading 2", 13, BLUE, 14, 7),
        ("Heading 3", 12, DARK_BLUE, 10, 5),
    ):
        style = styles[style_name]
        style.font.name = FONT
        style._element.rPr.rFonts.set(qn("w:ascii"), FONT)
        style._element.rPr.rFonts.set(qn("w:hAnsi"), FONT)
        style._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = rgb(color)
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True

    for style_name in ("List Bullet", "List Number"):
        style = styles[style_name]
        style.font.name = FONT
        style._element.rPr.rFonts.set(qn("w:ascii"), FONT)
        style._element.rPr.rFonts.set(qn("w:hAnsi"), FONT)
        style._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
        style.font.size = Pt(11)
        style.paragraph_format.left_indent = Inches(0.375)
        style.paragraph_format.first_line_indent = Inches(-0.188)
        style.paragraph_format.space_after = Pt(4)
        style.paragraph_format.line_spacing = 1.25

    header = section.header
    p = header.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    p.paragraph_format.space_after = Pt(0)
    run = p.add_run("巡检目录 I1｜Codex 模块化开发规范")
    set_run_font(run, size=9, color=MUTED, bold=True)

    footer = section.footer
    table = footer.add_table(rows=1, cols=2, width=Inches(6.5))
    set_table_geometry(table, [4680, 4680], indent_dxa=0)
    table.style = "Table Grid"
    for cell in table.rows[0].cells:
        tc_pr = cell._tc.get_or_add_tcPr()
        borders = tc_pr.find(qn("w:tcBorders"))
        if borders is None:
            borders = OxmlElement("w:tcBorders")
            tc_pr.append(borders)
        for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
            tag = OxmlElement(f"w:{edge}")
            tag.set(qn("w:val"), "nil")
            borders.append(tag)
    left = table.cell(0, 0).paragraphs[0]
    left.paragraph_format.space_after = Pt(0)
    run = left.add_run("V1.0｜2026-07-27")
    set_run_font(run, size=9, color=MUTED)
    right = table.cell(0, 1).paragraphs[0]
    right.paragraph_format.space_after = Pt(0)
    add_page_number(right)


def add_title(doc: Document, text: str, subtitle: str | None = None) -> None:
    p = doc.add_paragraph(style="Title")
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    run = p.add_run(text)
    set_run_font(run, size=25, color=INK, bold=True)
    if subtitle:
        p2 = doc.add_paragraph()
        p2.paragraph_format.space_after = Pt(18)
        run = p2.add_run(subtitle)
        set_run_font(run, size=14, color=MUTED)


def add_kicker(doc: Document, text: str) -> None:
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(10)
    p.paragraph_format.space_after = Pt(10)
    run = p.add_run(text.upper())
    set_run_font(run, size=10, color=ORANGE, bold=True)


def add_para(doc: Document, text: str, *, bold_prefix: str | None = None,
             color: str = INK, after: float | None = None) -> None:
    p = doc.add_paragraph()
    if after is not None:
        p.paragraph_format.space_after = Pt(after)
    if bold_prefix and text.startswith(bold_prefix):
        r1 = p.add_run(bold_prefix)
        set_run_font(r1, size=11, color=color, bold=True)
        r2 = p.add_run(text[len(bold_prefix):])
        set_run_font(r2, size=11, color=color)
    else:
        run = p.add_run(text)
        set_run_font(run, size=11, color=color)


def add_bullets(doc: Document, items: Iterable[str]) -> None:
    for item in items:
        p = doc.add_paragraph(style="List Bullet")
        run = p.add_run(item)
        set_run_font(run, size=11, color=INK)


def add_numbered(doc: Document, items: Iterable[str]) -> None:
    for index, item in enumerate(items, start=1):
        p = doc.add_paragraph()
        p.paragraph_format.left_indent = Inches(0.28)
        p.paragraph_format.first_line_indent = Inches(-0.22)
        run = p.add_run(f"{index}.  {item}")
        set_run_font(run, size=11, color=INK)


def add_callout(doc: Document, label: str, text: str,
                fill: str = ORANGE_LIGHT, border: str = ORANGE) -> None:
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Inches(0.1)
    p.paragraph_format.right_indent = Inches(0.08)
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(10)
    p.paragraph_format.line_spacing = 1.2
    set_paragraph_shading(p, fill)
    set_paragraph_border(p, border, size=20, side="left", space=8)
    r1 = p.add_run(f"{label}  ")
    set_run_font(r1, size=11, color=border, bold=True)
    r2 = p.add_run(text)
    set_run_font(r2, size=11, color=INK)


def add_code(doc: Document, text: str) -> None:
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Inches(0.2)
    p.paragraph_format.right_indent = Inches(0.2)
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(8)
    p.paragraph_format.line_spacing = 1.05
    set_paragraph_shading(p, LIGHTER)
    run = p.add_run(text)
    set_run_font(run, name=MONO, size=9.2, color=INK)


def add_table(doc: Document, headers: Sequence[str],
              rows: Sequence[Sequence[str]], widths: Sequence[int],
              font_size: float = 9.5) -> None:
    table = doc.add_table(rows=1, cols=len(headers))
    table.style = "Table Grid"
    set_table_geometry(table, widths)
    set_repeat_table_header(table.rows[0])
    for idx, header in enumerate(headers):
        cell = table.rows[0].cells[idx]
        set_cell_shading(cell, LIGHT)
        p = cell.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.space_after = Pt(0)
        r = p.add_run(header)
        set_run_font(r, size=font_size, color=DARK_BLUE, bold=True)
    for row_data in rows:
        row = table.add_row()
        for idx, value in enumerate(row_data):
            cell = row.cells[idx]
            set_cell_width(cell, widths[idx])
            set_cell_margins(cell)
            cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            p = cell.paragraphs[0]
            p.paragraph_format.space_after = Pt(0)
            p.paragraph_format.line_spacing = 1.12
            if len(value) <= 12 and idx != len(row_data) - 1:
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            else:
                p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            r = p.add_run(value)
            set_run_font(r, size=font_size, color=INK)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def add_figure(doc: Document, image_path: Path, title: str, description: str) -> None:
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(6)
    shape = p.add_run().add_picture(str(image_path), width=Inches(4.0))
    set_image_alt(shape, title, description)
    caption = doc.add_paragraph()
    caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
    caption.paragraph_format.space_after = Pt(6)
    run = caption.add_run(title)
    set_run_font(run, size=9.5, color=MUTED, italic=True)


def add_heading(doc: Document, text: str, level: int = 1) -> None:
    doc.add_heading(text, level=level)


def build() -> None:
    doc = Document()
    configure_document(doc)

    # Cover
    add_kicker(doc, "正式产品增量开发文档")
    add_title(
        doc,
        "巡检目录 I1\nCodex 模块化开发规范",
        "服务 → 我要巡检 → 巡检服务｜两模块首期开发",
    )
    add_table(
        doc,
        ["项目属性", "确定口径"],
        [
            ["产品性质", "正式产品的分阶段增量，不是静态 Demo"],
            ["当前范围", "INS-M-002 我要巡检；INS-M-003 巡检服务列表"],
            ["开发重点", "M02 搜索、分类、排序、城市、筛选、刷新和分页"],
            ["移动端", "Android、iOS 真机；未来微信小程序"],
            ["技术栈", "uni-app + Vue3 + TypeScript；Java21 + Spring Boot3.5"],
            ["数据", "PostgreSQL + PostGIS；Redis；MinIO"],
            ["版本日期", "V1.0｜2026-07-27"],
        ],
        [2160, 7200],
        font_size=10,
    )
    add_callout(
        doc,
        "执行口径",
        "Codex 必须逐任务、逐阶段开发。当前只实现两个页面和目录读取能力；服务详情、发布需求、咨询、预约、报价、订单和后台均不得擅自扩展。",
    )
    add_para(
        doc,
        "本 Word 文档用于产品负责人审阅；实际交给 Codex 执行时，以开发包中的 AGENTS.md、docs/ 与 contracts/ 为事实源。",
        color=MUTED,
    )
    doc.add_page_break()

    # Contents
    add_heading(doc, "文档导航", 1)
    add_table(
        doc,
        ["章节", "内容", "主要读者"],
        [
            ["1", "范围基线与固定决策", "产品负责人、Codex"],
            ["2", "两张 UI 参考图与必要修正", "产品、UI、前端"],
            ["3", "模块化架构与目录边界", "前端、后端、Codex"],
            ["4", "M01 我要巡检入口规格", "前端、测试"],
            ["5", "M02 巡检服务列表规格", "前端、后端、测试"],
            ["6", "路由、状态与超范围动作", "前端、产品"],
            ["7", "API 与 PostgreSQL/PostGIS", "后端、前端"],
            ["8", "Codex 分阶段执行计划", "产品负责人、Codex"],
            ["9", "测试、真机与完成定义", "测试、研发"],
            ["10", "待确认事项与交付使用方法", "产品负责人"],
        ],
        [900, 5580, 2880],
    )
    add_callout(
        doc,
        "先读结论",
        "这两个页面可以先独立开发，但它们只是完整巡检业务的 I1 目录增量。后续服务详情和需求承接未接入前，超范围入口必须受生产功能开关控制。",
        fill=LIGHTER,
        border=BLUE,
    )

    # 1
    add_heading(doc, "1. 范围基线与固定决策", 1)
    add_heading(doc, "1.1 本期只开发两个模块", 2)
    add_table(
        doc,
        ["编号", "代码名", "页面", "本期职责"],
        [
            ["INS-M-002", "inspection-portal", "我要巡检", "巡检入口、分类、保障、热门服务、流程"],
            ["INS-M-003", "inspection-catalog", "巡检服务", "搜索、筛选、排序、城市、刷新、分页"],
        ],
        [1350, 2070, 1800, 4140],
    )
    add_para(doc, "本期唯一需要打通的真实点击路径：")
    add_code(
        doc,
        "底部“服务”\n"
        "→ 点击“我要巡检”\n"
        "→ INS-M-002\n"
        "→ 分类 / 搜索 / 查看全部 / 更多场景\n"
        "→ INS-M-003\n"
        "→ 搜索 / 分类 / 排序 / 城市 / 筛选 / 刷新 / 分页",
    )
    add_heading(doc, "1.2 明确排除", 2)
    add_bullets(
        doc,
        [
            "巡检服务详情、发布巡检需求、咨询、预约、报价、订单、支付、执行、交付和验收。",
            "服务商/飞手工作台与 Web 管理后台页面。",
            "微信小程序、无人机飞行控制、直播、航线和 AI 缺陷识别算法。",
            "其他服务分类和一级导航改造。",
            "任何为了凑页面或看起来功能多而新增的营销入口。",
        ],
    )
    add_heading(doc, "1.3 固定技术与产品口径", 2)
    add_table(
        doc,
        ["事项", "固定决定"],
        [
            ["移动端", "uni-app + Vue3 + TypeScript + Pinia"],
            ["后端", "Java21 + Spring Boot3.5 + MyBatis-Plus"],
            ["数据", "PostgreSQL + PostGIS；Redis缓存；MinIO媒体"],
            ["架构", "模块化单体，不上微服务"],
            ["UI", "面向公众、生活服务化；橙色主色"],
            ["城市", "东营为首发配置，但由全局城市动态决定"],
            ["目录浏览", "默认允许未登录浏览"],
            ["服务卡", "平台标准化服务项目，不是具体服务商商品"],
        ],
        [2300, 7060],
    )
    add_callout(
        doc,
        "禁止误报",
        "I1 完成后只能标记“巡检目录 I1 完成”，不得声称完整巡检模块已完成或已经具备公开上线条件。",
        fill="FEECEB",
        border=RED,
    )

    # 2 images
    add_heading(doc, "2. UI 参考图与必要修正", 1)
    add_heading(doc, "2.1 INS-M-002：我要巡检入口", 2)
    add_figure(
        doc,
        REF_1,
        "图 1｜INS-M-002 我要巡检入口参考图",
        "东营城市头、我要巡检选中态、巡检Hero、八类巡检场景、服务保障、热门服务与服务流程。",
    )
    add_heading(doc, "2.2 INS-M-003：巡检服务列表", 2)
    add_figure(
        doc,
        REF_2,
        "图 2｜INS-M-003 巡检服务列表参考图",
        "巡检服务标题、搜索与筛选、分类胶囊、排序与城市、单列服务卡、底部服务Tab。",
    )
    add_heading(doc, "2.3 参考图冲突的固定处理", 2)
    add_table(
        doc,
        ["冲突", "实现口径"],
        [
            ["东营 / 济南", "共享 currentCity；两页保持一致"],
            ["油气 / 燃气", "编码 OIL_GAS；分类默认显示“油气”"],
            ["搜索服务商", "本期统一为“服务、场景或关键词”"],
            ["示例数字", "真实数据或隐藏，禁止硬编码"],
            ["参考图同屏四卡", "真机横向滚动，保证可读和可触控"],
        ],
        [2400, 6960],
    )

    # 3
    add_heading(doc, "3. 模块化架构与代码边界", 1)
    add_heading(doc, "3.1 依赖方向", 2)
    add_code(
        doc,
        "移动端：presentation → application → domain\n"
        "                         ↑\n"
        "                 data repositories\n\n"
        "服务端：interfaces → application → domain\n"
        "                         ↑\n"
        "                infrastructure adapters",
    )
    add_para(
        doc,
        "domain 不依赖 Vue、uni-app、HTTP、数据库或具体 UI 组件。其他业务模块不得直接读取巡检模块的内部 Store、Mapper、缓存键或数据库表。",
    )
    add_heading(doc, "3.2 移动端建议目录", 2)
    add_code(
        doc,
        "features/inspection/\n"
        "├── public.ts\n"
        "├── domain/\n"
        "├── application/\n"
        "├── data/\n"
        "├── presentation/\n"
        "│   ├── portal/\n"
        "│   ├── catalog/\n"
        "│   └── shared/\n"
        "├── state/\n"
        "├── navigation/\n"
        "└── tests/",
    )
    add_heading(doc, "3.3 服务端建议目录", 2)
    add_code(
        doc,
        "modules/inspection/\n"
        "├── domain/\n"
        "├── application/query/\n"
        "├── infrastructure/\n"
        "│   ├── persistence/\n"
        "│   ├── cache/\n"
        "│   ├── media/\n"
        "│   └── location/\n"
        "├── interfaces/app-api/\n"
        "├── migrations/\n"
        "└── tests/",
    )
    add_heading(doc, "3.4 允许复用与禁止复制", 2)
    add_table(
        doc,
        ["公共能力", "本期规则"],
        [
            ["底部导航", "复用公共壳，不在巡检模块重建"],
            ["城市", "复用全局 Store，不另建巡检城市"],
            ["天气/消息/客服", "复用真实网关；不存在则生产隐藏"],
            ["网络/错误/日志", "沿用现有统一能力"],
            ["UI组件库", "沿用现有；仅定制巡检业务组件"],
            ["位置/坐标", "通过适配器；统一WGS84/SRID4326"],
        ],
        [2400, 6960],
    )

    # 4
    add_heading(doc, "4. M01 我要巡检入口规格", 1)
    add_heading(doc, "4.1 页面组件", 2)
    add_code(
        doc,
        "InspectionPortalPage\n"
        "├── PublicServiceHeader\n"
        "├── PublicServiceTypeTabs\n"
        "├── InspectionHero\n"
        "├── InspectionCategoryGrid\n"
        "├── InspectionGuaranteeStrip\n"
        "├── HotInspectionServices\n"
        "├── InspectionProcess\n"
        "└── PublicAppTabBar",
    )
    add_heading(doc, "4.2 关键交互", 2)
    add_table(
        doc,
        ["控件", "本期行为", "数据/约束"],
        [
            ["搜索", "进入M02并传keyword/cityCode", "最大50字符"],
            ["普通场景", "进入M02并传categoryCode", "使用稳定编码"],
            ["更多场景", "进入M02并首次打开筛选", "一次性路由状态"],
            ["查看全部", "进入M02全部列表", "清空分类"],
            ["热门服务", "发出详情意图", "未接入时生产关闭"],
            ["发布需求", "发出需求意图", "本期不建表单页"],
        ],
        [1800, 3540, 4020],
    )
    add_heading(doc, "4.3 场景分类", 2)
    add_table(
        doc,
        ["编码", "默认名称", "入口副标题"],
        [
            ["POWER", "电力巡检", "线路·杆塔·变电站"],
            ["PHOTOVOLTAIC", "光伏巡检", "热斑·组件·效率"],
            ["OIL_GAS", "油气巡检", "管线·泄漏·安全"],
            ["THERMAL", "热力巡检", "热成像·管网评估"],
            ["PARK", "园区巡检", "安防·设备·环境"],
            ["EQUIPMENT", "设备巡检", "状态·故障·记录"],
            ["PERIODIC", "定期巡检", "周期计划·持续跟踪"],
            ["MORE", "更多场景", "按需求定制方案"],
        ],
        [2300, 2500, 4560],
    )
    add_heading(doc, "4.4 页面状态", 2)
    add_code(doc, "INITIAL → LOADING → READY\n                  ├→ PARTIAL_ERROR\n                  └→ ERROR")
    add_bullets(
        doc,
        [
            "Header保持稳定；Banner、宫格和热门服务使用骨架。",
            "单区块失败时局部重试，不让整页失效。",
            "无当前城市时先选择城市，不展示其他城市价格。",
            "热门服务无数据时隐藏整个区块。",
        ],
    )

    # 5
    add_heading(doc, "5. M02 巡检服务列表规格", 1)
    add_heading(doc, "5.1 页面组件", 2)
    add_code(
        doc,
        "InspectionCatalogPage\n"
        "├── SubPageHeader\n"
        "├── CatalogSearchRow\n"
        "├── InspectionCategoryChips\n"
        "├── InspectionSortBar\n"
        "├── CatalogStatePanel\n"
        "│   ├── InspectionServiceCard[]\n"
        "│   ├── Skeleton / Empty / Error\n"
        "│   └── LoadMoreState\n"
        "├── InspectionFilterSheet\n"
        "└── PublicAppTabBar",
    )
    add_heading(doc, "5.2 查询与排序", 2)
    add_table(
        doc,
        ["能力", "规则"],
        [
            ["搜索", "中文输入法组词期间不查询；新请求覆盖旧请求资格"],
            ["分类", "单选横向胶囊；切换后重置分页"],
            ["综合", "平台权重后以serviceId稳定排序"],
            ["距离", "真实授权、位置和覆盖范围；拒绝后回退"],
            ["价格", "升序/降序明确；项目报价不参与虚假金额比较"],
            ["城市", "切换后清理不适用筛选并重新请求"],
            ["刷新", "第一页重新请求；失败保留旧数据"],
            ["分页", "防并发、按ID去重、末页停止请求"],
        ],
        [1900, 7460],
    )
    add_heading(doc, "5.3 服务卡片", 2)
    add_bullets(
        doc,
        [
            "单列左图右文，左图宽度约112–136dp，右侧设置可收缩宽度。",
            "标题和摘要最多两行，标签最多三项并允许合理换行。",
            "主动作按钮触控区至少88×44dp。",
            "价格使用人民币分的整数模型；PROJECT_QUOTE只显示“按项目报价”。",
            "评分与服务次数没有真实值时隐藏。",
            "卡片和咨询/预约统一发出类型化ServiceIntent。",
        ],
    )
    add_heading(doc, "5.4 高级筛选", 2)
    add_table(
        doc,
        ["字段", "首期选项"],
        [
            ["服务方式", "现场巡检、周期巡检、定制方案"],
            ["可用状态", "当前可服务、可预约"],
            ["价格模式", "按公里、按次、按项目报价"],
            ["交付能力", "是否包含报告"],
        ],
        [2200, 7160],
    )
    add_para(
        doc,
        "筛选层是 M02 内部 bottom sheet：打开时复制临时草稿，确认后应用；取消不改变已应用条件；重置不清空城市和关键词。",
    )

    # 6
    add_heading(doc, "6. 路由、状态与超范围动作", 1)
    add_heading(doc, "6.1 建议路由", 2)
    add_table(
        doc,
        ["页面", "建议路径", "主要参数"],
        [
            ["INS-M-002", "/pages/service/inspection/index", "无；读取全局城市"],
            ["INS-M-003", "/pages/service/inspection/catalog", "cityCode、categoryCode、keyword、sort、source"],
        ],
        [1800, 3600, 3960],
    )
    add_heading(doc, "6.2 状态管理", 2)
    add_bullets(
        doc,
        [
            "currentCity复用公共Store。",
            "Portal Store保存入口快照、配置版本、滚动恢复和区块状态。",
            "Catalog Store保存查询条件、列表、分页、请求标识和错误状态。",
            "弹层开关、输入焦点和临时筛选草稿保留在组件本地。",
            "经纬度不长期写入路由或Store。",
        ],
    )
    add_heading(doc, "6.3 超范围动作", 2)
    add_code(
        doc,
        "InspectionHostActionPort\n"
        "├── openCitySelector()\n"
        "├── openMessageCenter()\n"
        "├── openCustomerService()\n"
        "├── openInspectionServiceDetail(serviceId)\n"
        "├── startInspectionRequest(city/category/service)\n"
        "└── openRootTab(tab)",
    )
    add_callout(
        doc,
        "生产门禁",
        "未实现的详情、需求、咨询和预约必须通过功能开关关闭。不得留无响应按钮，不得跳空白页，也不得用“提交成功”假数据冒充。",
    )

    # 7
    add_heading(doc, "7. API 与 PostgreSQL/PostGIS", 1)
    add_heading(doc, "7.1 本期 API", 2)
    add_code(
        doc,
        "GET /api/v1/inspection/portal\n"
        "GET /api/v1/inspection/filters\n"
        "GET /api/v1/inspection/services",
    )
    add_table(
        doc,
        ["接口", "职责", "关键参数"],
        [
            ["/portal", "入口配置、分类、保障、热门、流程", "cityCode"],
            ["/filters", "当前城市可用筛选项", "cityCode"],
            ["/services", "目录搜索与分页", "城市、关键词、分类、筛选、排序、位置、分页"],
        ],
        [1900, 3300, 4160],
    )
    add_heading(doc, "7.2 价格与统计", 2)
    add_bullets(
        doc,
        [
            "金额用人民币分long，不用float/double。",
            "价格模式：STARTING_PRICE、PER_DISTANCE、PER_VISIT、PROJECT_QUOTE。",
            "评分和完成数只读取真实聚合；无数据返回null并隐藏。",
            "参考图价格和统计只能作为开发测试场景，不自动进入生产。",
        ],
    )
    add_heading(doc, "7.3 最小数据对象", 2)
    add_table(
        doc,
        ["表", "职责"],
        [
            ["inspection_category", "巡检分类和入口副标题"],
            ["inspection_service", "平台标准服务基础信息"],
            ["inspection_service_city", "城市开通、价格、热门和排序"],
            ["inspection_service_tag", "能力/方式/交付标签"],
            ["inspection_service_tag_relation", "服务与标签关系"],
            ["inspection_portal_content", "Hero、保障、流程和动作配置"],
            ["inspection_service_coverage", "PostGIS覆盖范围和距离口径"],
        ],
        [3400, 5960],
    )
    add_heading(doc, "7.4 PostGIS 规则", 2)
    add_bullets(
        doc,
        [
            "数据库统一WGS84、SRID 4326。",
            "高德/腾讯GCJ-02通过位置适配层转换。",
            "覆盖区域建立GIST索引。",
            "距离口径只能选择到覆盖区域或到中心点，并在接口中明确。",
            "无位置或无真实覆盖数据时不开放距离排序。",
        ],
    )
    add_callout(
        doc,
        "契约文件",
        "机器可读接口位于 contracts/openapi-inspection-catalog.yaml；示例位于 contracts/inspection-catalog.examples.json，后者明确仅用于开发与契约测试。",
        fill=LIGHTER,
        border=BLUE,
    )

    # 8
    add_heading(doc, "8. Codex 分阶段执行计划", 1)
    add_para(doc, "单个任务控制在30–60分钟；到用户验收点必须停止。")
    add_table(
        doc,
        ["任务", "内容", "停止/验收"],
        [
            ["T01", "项目盘点、AGENTS、技术栈、公共能力、冲突", "验收点；不得编码"],
            ["T02", "模块边界与契约映射", "继续"],
            ["T03", "模块骨架、路由和真实入口", "继续"],
            ["T04", "M01上半屏", "继续"],
            ["T05", "M01下半屏、状态与视觉", "验收M01"],
            ["T06", "M02顶部、搜索与分类", "继续"],
            ["T07", "M02排序、筛选与卡片", "继续"],
            ["T08", "M02状态、分页与视觉", "验收M02"],
            ["T09", "PostgreSQL/PostGIS迁移", "继续"],
            ["T10", "三个只读API及测试", "验收API"],
            ["T11", "移动端接入真实API", "继续"],
            ["T12", "自动化、视觉和性能", "继续"],
            ["T13", "Android真机", "继续"],
            ["T14", "iOS真机与增量交付", "最终验收"],
        ],
        [900, 5940, 2520],
        font_size=9.2,
    )
    add_callout(
        doc,
        "第一次只做 T01",
        "Codex 首次读取开发包后，只允许输出项目盘点、可复用能力、拟改目录、冲突和下一阶段计划。不得立即生成大量代码。",
    )

    # 9
    add_heading(doc, "9. 测试、真机与完成定义", 1)
    add_heading(doc, "9.1 必测链路", 2)
    add_numbered(
        doc,
        [
            "底部“服务”→“我要巡检”→分类→巡检服务列表。",
            "入口搜索→列表关键词与城市正确。",
            "分类、价格排序、筛选、切城、刷新和分页。",
            "快速切换条件时旧响应不覆盖。",
            "拒绝定位时距离排序降级，普通浏览继续。",
            "断网、超时、空城市、空筛选和重试。",
        ],
    )
    add_heading(doc, "9.2 视觉与真机", 2)
    add_table(
        doc,
        ["平台/宽度", "重点"],
        [
            ["320/360", "无横向溢出、中文竖排和按钮遮挡"],
            ["375/390", "主要视觉基准、搜索、卡片、底部导航"],
            ["412/430", "内容不被过度拉伸"],
            ["Android真机", "返回键、定位、中文键盘、弱网"],
            ["iPhone真机", "刘海/灵动岛、底部手势、右滑返回、定位"],
        ],
        [2400, 6960],
    )
    add_heading(doc, "9.3 I1 完成定义", 2)
    add_bullets(
        doc,
        [
            "从真实公共入口可达两页。",
            "M01/M02模块边界明确，公共能力不复制。",
            "城市一致，不发生东营/济南跳变。",
            "搜索、筛选、排序、切城、刷新、分页来自真实API。",
            "生产构建没有Fixture和假统计。",
            "Android、iOS真机无溢出、遮挡和中文竖排。",
            "数据库迁移、OpenAPI、测试和运行说明完整。",
            "每个阶段得到用户验收。",
        ],
    )

    # 10
    add_heading(doc, "10. 待确认事项与交付使用方法", 1)
    add_heading(doc, "10.1 不阻塞静态UI但阻塞生产发布", 2)
    add_numbered(
        doc,
        [
            "首批真实服务名称、价格模式和正式价格。",
            "东营首批真实可服务范围。",
            "正式图片的来源与商业授权。",
            "客服、天气、消息和定位公共能力是否已经存在。",
            "评分和服务完成数的真实统计来源。",
            "距离是到覆盖区域、服务商还是作业中心。",
            "目录数据维护责任人和管理后台上线时间。",
        ],
    )
    add_heading(doc, "10.2 开发包内容", 2)
    add_table(
        doc,
        ["文件/目录", "作用"],
        [
            ["AGENTS.md", "Codex强制规范和停止规则"],
            ["START-CODEX.md", "第一次启动指令"],
            ["docs/00–10", "范围、架构、UI、路由、API、数据、测试、任务和资产"],
            ["contracts/openapi…yaml", "机器可读API事实源"],
            ["contracts/…examples.json", "仅开发/契约测试的示例"],
            ["references/", "两张UI视觉参考图"],
        ],
        [3500, 5860],
    )
    add_heading(doc, "10.3 第一次发给 Codex 的指令", 2)
    add_code(
        doc,
        "请完整读取项目根目录 AGENTS.md、本开发包全部 docs/、contracts/ 与两张参考图。\n"
        "本轮只执行 T01：项目盘点与差异检查，不得开始 T02，不得创建大批业务代码。\n"
        "完成后列出技术栈、公共能力、拟改目录、路由、接口、数据库表、冲突和待确认问题，\n"
        "然后停止，等待我回复“验收通过”。",
    )
    add_callout(
        doc,
        "最终建议",
        "先验收 M01，再验收 M02，然后接真实 PostgreSQL/PostGIS 与 API。这样既能限制 Codex 自由发挥，也不会把静态 UI 误认为正式功能完成。",
    )

    # Core properties
    doc.core_properties.title = "巡检目录I1 Codex模块化开发规范 V1.0"
    doc.core_properties.subject = "服务-我要巡检-巡检服务两模块正式产品开发规范"
    doc.core_properties.author = "山东沉积岩信息科技有限公司"
    doc.core_properties.keywords = "巡检服务, Codex, 模块化开发, uni-app, Spring Boot, PostgreSQL, PostGIS"

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    doc.save(OUTPUT)
    print(OUTPUT)


if __name__ == "__main__":
    build()
