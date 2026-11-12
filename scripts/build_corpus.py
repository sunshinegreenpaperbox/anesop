# -*- coding: utf-8 -*-
"""把《手术麻醉信息系统 · 施工前方案书》docx 转成可投喂 AI 的语料包。

输出（corpus/）：
- chapters/NN_章名.md        按章拆分的干净 Markdown（含表格）
- chunks.jsonl               按节切分的检索块（含元数据、字符数、token 数）
- structured/*.yaml          从文档表格抽取的结构化事实（模块/角色/集成/NFR/栈/里程碑/迭代/决策/待确认/交付物）
- INDEX.md                   语料索引与 token 账单
- manifest.json              生成信息与统计
- reports/token_report.md    token 分布

用法（仓库根）：PYTHONPATH=./.pylibs python3 scripts/build_corpus.py
"""
import os
import re
import json
import glob
import hashlib
from datetime import datetime

from docx import Document
from docx.table import Table
from docx.text.paragraph import Paragraph
from docx.oxml.ns import qn
import yaml
import tiktoken

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCX = os.path.join(ROOT, "docs", "plan", "手术麻醉信息系统_施工前方案书_V0.9.docx")
OUT = os.path.join(ROOT, "docs", "ai", "corpus")
CH_DIR = os.path.join(OUT, "chapters")
ST_DIR = os.path.join(OUT, "structured")
REP_DIR = os.path.join(OUT, "reports")
ENC = tiktoken.get_encoding("cl100k_base")

HEADING_STYLE = {"Heading 1": 1, "Heading 2": 2, "Heading 3": 3, "Heading 4": 4}
CJK = re.compile(r"[\u4e00-\u9fff]")


def tokens(text):
    return len(ENC.encode(text, disallowed_special=()))


def cjk_count(text):
    return len(CJK.findall(text))


def clean_cell(text):
    t = re.sub(r"\s+", " ", text or "").strip()
    return t.replace("|", "\\|")


def table_to_md(tbl):
    rows = []
    for row in tbl.rows:
        cells = [clean_cell(c.text) for c in row.cells]
        rows.append(cells)
    if not rows:
        return ""
    ncol = max(len(r) for r in rows)
    rows = [(r + [""] * ncol)[:ncol] for r in rows]
    lines = ["| " + " | ".join(rows[0]) + " |",
             "|" + "|".join(["---"] * ncol) + "|"]
    for r in rows[1:]:
        lines.append("| " + " | ".join(r) + " |")
    return "\n".join(lines)


def parse_docx(path):
    doc = Document(path)
    blocks = []
    started = False
    for child in doc.element.body.iterchildren():
        if child.tag == qn("w:p"):
            p = Paragraph(child, doc)
            text = p.text.strip()
            style = p.style.name if p.style is not None else ""
            level = HEADING_STYLE.get(style)
            if level:
                if not started and level == 1:
                    started = True
                if not started:
                    continue
                blocks.append((f"h{level}", text))
                continue
            if not started or not text:
                continue
            if "目　录" in text or "目 录" in text:
                continue
            blocks.append(("p", text))
        elif child.tag == qn("w:tbl"):
            if not started:
                continue
            t = Table(child, doc)
            md = table_to_md(t)
            if md:
                blocks.append(("table", md))
    return blocks


# ---------------------------------------------------------------- 章节组装
def chapter_no(title):
    m = re.match(r"第\s*(\d+)\s*章", title)
    if m:
        return int(m.group(1))
    return 90


def build_chapters(blocks):
    chapters = []
    cur = None
    for kind, text in blocks:
        if kind == "h1":
            cur = {"no": chapter_no(text), "title": text, "blocks": []}
            chapters.append(cur)
        elif cur is not None:
            cur["blocks"].append((kind, text))
    chapters.sort(key=lambda c: c["no"])
    return chapters


def chapter_md(ch):
    lines = [f"# {ch['title']}", ""]
    for kind, text in ch["blocks"]:
        if kind == "h2":
            lines += ["", f"## {text}", ""]
        elif kind == "h3":
            lines += ["", f"### {text}", ""]
        elif kind == "h4":
            lines += ["", f"#### {text}", ""]
        elif kind == "p":
            lines += [text, ""]
        elif kind == "table":
            lines += [text, ""]
    return "\n".join(lines).strip() + "\n"


# ---------------------------------------------------------------- 结构化表格
KEYMAP = {
    "编号": "id", "序号": "id",
    "主题": "topic", "级别": "priority", "建议方案（一句话）": "suggestion",
    "建议方案": "suggestion", "拍板人": "owner", "期限": "deadline",
    "模块": "module", "目标用户": "users", "核心能力": "capabilities", "关键输出": "outputs",
    "角色": "role", "主要诉求": "needs",
    "系统": "system", "集成内容": "content", "方式（建议）": "method", "方向": "direction",
    "类别": "category", "指标": "target", "要求": "requirement", "本项目措施": "measure",
    "层次": "layer", "选型": "choice", "版本基线": "version", "说明": "note",
    "里程碑": "milestone", "名称": "name", "日期": "date", "交付物": "deliverable",
    "时点": "milestone", "迭代": "sprint", "周期": "duration", "起止": "window",
    "目标": "goal", "阶段": "phase", "内容": "content", "产出": "output",
    "问题": "question", "影响": "impact", "验收形式": "acceptance",
    "用途": "purpose", "核心职责": "duty", "主要使用模块": "modules",
    "集成内容与方式（方向）": "content", "集成内容与方式": "content",
}


def norm_header(h):
    h = re.sub(r"\s+", "", h or "")
    return KEYMAP.get(h, h)


def table_dicts(tbl):
    rows = [[clean_cell(c.text) for c in row.cells] for row in tbl.rows]
    if len(rows) < 2:
        return []
    header = [norm_header(h) for h in rows[0]]
    out = []
    for r in rows[1:]:
        if not any(r):
            continue
        item = {}
        for i, key in enumerate(header):
            item[key] = r[i] if i < len(r) else ""
        out.append(item)
    return out


def extract_structured(path):
    doc = Document(path)
    found = {}
    signatures = [
        ("modules", lambda h: "模块" in h and "核心能力" in h),
        ("roles", lambda h: "角色" in h and "核心职责" in h),
        ("integrations", lambda h: "系统" in h and any("集成内容" in x for x in h)),
        ("nfr", lambda h: h and h[0] == "类别" and "指标" in h and "优先级" not in h),
        ("stack", lambda h: h and h[0] == "层次"),
        ("milestones", lambda h: h and h[0] == "里程碑"),
        ("sprints", lambda h: h and h[0] == "迭代"),
        ("decisions", lambda h: "主题" in h and "拍板人" in h),
        ("questions", lambda h: h and h[0] == "序号" and "问题" in h),
        ("deliverables", lambda h: h and h[0] == "编号" and "交付物" in h and "验收形式" in h),
    ]
    for tbl in doc.tables:
        header = [clean_cell(c.text) for c in tbl.rows[0].cells] if tbl.rows else []
        joined = "".join(header)
        for name, pred in signatures:
            if name in found:
                continue
            if pred(header):
                rows = table_dicts(tbl)
                if rows:
                    found[name] = rows
                break
    return found


# ---------------------------------------------------------------- 切块
LIMIT = 1800


def blocks_text(blocks):
    parts = []
    for kind, text in blocks:
        if kind == "h3":
            parts.append(f"### {text}")
        elif kind == "h2":
            parts.append(f"## {text}")
        else:
            parts.append(text)
    return "\n\n".join(parts)


def split_by_h3(blocks):
    groups = []
    cur = []
    for b in blocks:
        if b[0] == "h3" and cur:
            groups.append(cur)
            cur = [b]
        else:
            cur.append(b)
    if cur:
        groups.append(cur)
    return groups


def split_long(blocks, limit=LIMIT):
    out = []
    cur = []
    size = 0
    for b in blocks:
        t = len(b[1])
        if cur and size + t > limit:
            out.append(cur)
            cur = [b]
            size = t
        else:
            cur.append(b)
            size += t
    if cur:
        out.append(cur)
    return out


def build_chunks(chapters):
    chunks = []
    for ch in chapters:
        sec_no = 0
        sec_blocks = []
        sec_title = ch["title"]
        sec_level = 1

        def flush():
            nonlocal sec_blocks, sec_title, sec_level, sec_no
            if not sec_blocks:
                return
            path = f"{ch['title']} > {sec_title}" if sec_level > 1 else ch["title"]
            groups = split_by_h3(sec_blocks) if len(blocks_text(sec_blocks)) > LIMIT else [sec_blocks]
            all_parts = []
            for g in groups:
                all_parts.extend(split_long(g) if len(blocks_text(g)) > LIMIT else [g])
            total = len(all_parts)
            for k, part in enumerate(all_parts, 1):
                body = blocks_text(part)
                has_table = any(b[0] == "table" for b in part)
                text = f"{path}\n\n{body}"
                if total > 1:
                    text = f"{path}（第 {k}/{total} 部分）\n\n{body}"
                chunks.append({
                    "id": f"CH{ch['no']:02d}-S{sec_no:02d}" + (f"-P{k}" if total > 1 else ""),
                    "chapter": ch["title"],
                    "chapter_no": ch["no"],
                    "section": sec_title,
                    "heading_path": path,
                    "kind": "table" if has_table else "prose",
                    "chars": len(text),
                    "cjk": cjk_count(text),
                    "tokens": tokens(text),
                    "text": text,
                })
            sec_blocks = []

        for kind, text in ch["blocks"]:
            if kind == "h2":
                flush()
                sec_no += 1
                sec_title = text
                sec_level = 2
            else:
                sec_blocks.append((kind, text))
        if not sec_blocks and sec_title and not any(b for b in [1]):
            pass
        if sec_blocks:
            if sec_no == 0:
                sec_no = 1
            flush()
    return chunks


# ---------------------------------------------------------------- 输出
def write_yaml(name, data, note=""):
    path = os.path.join(ST_DIR, name)
    with open(path, "w", encoding="utf-8") as f:
        if note:
            f.write(f"# {note}\n")
        f.write(yaml.safe_dump(data, allow_unicode=True, sort_keys=False, width=120))
    return path


def main():
    for d in (CH_DIR, ST_DIR, REP_DIR):
        os.makedirs(d, exist_ok=True)
    for old in glob.glob(os.path.join(CH_DIR, "*.md")):
        os.remove(old)
    with open(DOCX, "rb") as f:
        digest = hashlib.sha256(f.read()).hexdigest()[:16]

    blocks = parse_docx(DOCX)
    chapters = build_chapters(blocks)

    # 章节 md
    ch_files = []
    for ch in chapters:
        prefix = f"{ch['no']:02d}" if ch["no"] < 90 else "09"
        clean_title = re.sub(r"^第\s*\d+\s*章\s*", "", ch["title"])
        name = f"{prefix}_{re.sub(r'[^\u4e00-\u9fffA-Za-z0-9]+', '', clean_title)[:18]}.md"
        path = os.path.join(CH_DIR, name)
        content = chapter_md(ch)
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        ch_files.append({"file": f"chapters/{name}", "title": ch["title"],
                         "chars": len(content), "cjk": cjk_count(content),
                         "tokens": tokens(content)})

    # 结构化
    structured = extract_structured(DOCX)
    st_meta = {}
    st_notes = {
        "modules": "M0–M8 模块清单（第 2 章表 2-2）",
        "roles": "R1–R12 角色（第 2 章表 2-1）",
        "integrations": "I-01–I-14 外部系统集成（第 2 章表 2-5）",
        "nfr": "非功能需求基线（第 2 章表 2-6）",
        "stack": "技术栈基线（第 3 章表 3-3）",
        "milestones": "M0–M7 里程碑（第 6 章表 6-2）",
        "sprints": "S0–S12 迭代计划（第 6 章表 6-3）",
        "decisions": "D-01–D-22 待决策（第 7 章表 7-2）",
        "questions": "Q-01–Q-15 待客户确认（第 7 章表 7-6）",
        "deliverables": "DL-01–DL-21 交付物（第 8 章表 8-1）",
    }
    for name, rows in structured.items():
        p = write_yaml(f"{name}.yaml", rows, st_notes.get(name, ""))
        st_meta[name] = {"file": f"structured/{name}.yaml", "rows": len(rows),
                         "tokens": tokens(open(p, encoding="utf-8").read())}

    # 图清单（供视觉/引用场景）
    figures = [
        {"fig": "图 2-1", "file": "assets/figs/fig_2_1.png", "title": "系统功能架构",
         "caption": "M0 公共支撑与 M1–M8 八大模块分层支撑围术期全流程。"},
        {"fig": "图 2-2", "file": "assets/figs/fig_2_2.png", "title": "围术期核心业务泳道流程",
         "caption": "九个环节跨五类角色流转，四个异常分支均须留痕可追溯。"},
        {"fig": "图 3-1", "file": "assets/figs/fig_3_1.png", "title": "总体技术架构",
         "caption": "六层总体架构采用模块化单体、契约优先与独立采集网关。"},
        {"fig": "图 3-2", "file": "assets/figs/fig_3_2.png", "title": "集成与数据流拓扑",
         "caption": "以标准接口与院内系统双向集成，患者数据不出院。"},
        {"fig": "图 3-3", "file": "assets/figs/fig_3_3.png", "title": "部署架构（高可用）",
         "caption": "四类网络分区隔离，应用双节点与数据库主备保障高可用。"},
        {"fig": "图 4-1", "file": "assets/figs/fig_4_1.png", "title": "GitHub 工程协作闭环",
         "caption": "从 Issue 到发布的八步闭环，CI 红灯即回退修复。"},
        {"fig": "图 4-2", "file": "assets/figs/fig_4_2.png", "title": "分支模型与发布节奏",
         "caption": "主干持续集成、短分支当日合回，release 仅修缺陷。"},
        {"fig": "图 5-1", "file": "assets/figs/fig_5_1.png", "title": "多智能体协作与上下文分层",
         "caption": "任务卡驱动六个智能体，人工复核闸门守住医疗安全。"},
        {"fig": "图 6-1", "file": "assets/figs/fig_6_1.png", "title": "项目里程碑甘特图",
         "caption": "七阶段 31 周推进，八个里程碑逐段验收。"},
    ]
    write_yaml("figures.yaml", figures, "附图清单：文件路径、图题与一句话图注（视觉模型或引用场景使用）")
    st_meta["figures"] = {"file": "structured/figures.yaml", "rows": len(figures),
                          "tokens": tokens(open(os.path.join(ST_DIR, "figures.yaml"), encoding="utf-8").read())}

    # 切块
    chunks = build_chunks(chapters)
    with open(os.path.join(OUT, "chunks.jsonl"), "w", encoding="utf-8") as f:
        for c in chunks:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    # 全量单文件（仅当模型上下文足够大时使用）
    full_path = os.path.join(OUT, "full.md")
    with open(full_path, "w", encoding="utf-8") as f:
        f.write("# 手术麻醉信息系统 · 施工前方案书（全量语料）\n\n")
        for ch in chapters:
            f.write(chapter_md(ch))
            f.write("\n\n")
    full_tokens = tokens(open(full_path, encoding="utf-8").read())

    # 统计
    total_chars = sum(c["chars"] for c in chunks)
    total_tokens = sum(c["tokens"] for c in chunks)
    chunk_tokens = [c["tokens"] for c in chunks]
    per_chapter = {}
    for c in chunks:
        d = per_chapter.setdefault(c["chapter"], {"chunks": 0, "tokens": 0, "chars": 0})
        d["chunks"] += 1
        d["tokens"] += c["tokens"]
        d["chars"] += c["chars"]

    # INDEX.md
    lines = ["# ANESOP 方案书 AI 语料索引", "",
             f"- 生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M')}",
             f"- 源文件：手术麻醉信息系统_施工前方案书_V0.9.docx（sha256:{digest}）",
             f"- 规模：{len(chapters)} 个一级章节（含附录）、{len(chunks)} 个检索块、{total_chars} 字符、约 {total_tokens} tokens（cl100k）",
             f"- 全量单文件 `full.md`：约 {full_tokens} tokens（仅大上下文模型全量投喂时使用）",
             "",
             "## 1. 常驻层（每次对话都加载）", "",
             "| 文件 | 用途 | tokens | 建议加载 |",
             "|---|---|---|---|"]
    curated = [("CONTEXT_CORE.md", "项目一页纸：范围、模块、栈、铁律、红线", "始终"),
               ("AGENTS.fragment.md", "编码代理规则片段（可并入仓库 AGENTS.md）", "始终"),
               ("facts.yaml", "假设、团队、约束、术语", "始终或按需")]
    for fn, use, when in curated:
        fp = os.path.join(OUT, fn)
        tk = tokens(open(fp, encoding="utf-8").read()) if os.path.exists(fp) else "-"
        lines.append(f"| `{fn}` | {use} | {tk} | {when} |")
    lines += ["",
             "## 2. 结构化事实层（按任务取，不整篇读）", "",
             "| 文件 | 内容 | 条目 | tokens |",
             "|---|---|---|---|"]
    st_titles = {"modules": "模块清单", "roles": "角色", "integrations": "外部集成",
                 "nfr": "非功能指标", "stack": "技术栈", "milestones": "里程碑",
                 "sprints": "迭代", "decisions": "待决策 D-xx", "questions": "待确认 Q-xx",
                 "deliverables": "交付物 DL-xx"}
    for name, m in st_meta.items():
        lines.append(f"| `{m['file']}` | {st_titles.get(name, name)} | {m['rows']} | {m['tokens']} |")
    lines += ["", "## 3. 章节全文（按需加载）", "",
              "| 文件 | 章节 | 字符 | tokens |", "|---|---|---|---|"]
    for f in ch_files:
        lines.append(f"| `{f['file']}` | {f['title']} | {f['chars']} | {f['tokens']} |")
    lines += ["", "## 4. 检索块（RAG 入库）", "",
              f"`chunks.jsonl` 共 {len(chunks)} 条，字段：id / chapter / section / heading_path / kind / chars / cjk / tokens / text。",
              "", "按章分布：", "",
              "| 章节 | 块数 | 字符 | tokens |", "|---|---|---|---|"]
    for t, d in sorted(per_chapter.items(), key=lambda x: x[0]):
        lines.append(f"| {t} | {d['chunks']} | {d['chars']} | {d['tokens']} |")
    lines += ["", "> 每块 ≤ 1800 字符，超长节按三级标题或段落切分；块首包含 heading_path，可直接用于引用。"]
    with open(os.path.join(OUT, "INDEX.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    # token report
    rep = ["# Token 账单与切块质量", "",
           f"- 语料块：{len(chunks)} 个",
           f"- 总字符：{total_chars}；汉字：{sum(c['cjk'] for c in chunks)}",
           f"- 总 tokens（cl100k_base）：{total_tokens}",
           f"- 平均块：{total_chars // max(1, len(chunks))} 字符 / {total_tokens // max(1, len(chunks))} tokens",
           f"- 最大块：{max(chunk_tokens)} tokens；最小块：{min(chunk_tokens)} tokens",
           f"- 章节 md 合计：{sum(f['tokens'] for f in ch_files)} tokens",
           "", "> cl100k 为 GPT 系分词器，对中文与表格符号偏保守；DeepSeek/Qwen 处理中文为主的文本时通常比该值低 30%–50%。实际投喂前请用目标模型 tokenizer 复核，本表数字可视为上限。",
           "", "## 块大小分布", ""]
    buckets = {"<300": 0, "300-600": 0, "600-1000": 0, "1000-1500": 0, ">1500": 0}
    for t in chunk_tokens:
        if t < 300:
            buckets["<300"] += 1
        elif t < 600:
            buckets["300-600"] += 1
        elif t < 1000:
            buckets["600-1000"] += 1
        elif t < 1500:
            buckets["1000-1500"] += 1
        else:
            buckets[">1500"] += 1
    for k, v in buckets.items():
        rep.append(f"- {k} tokens：{v} 块")
    with open(os.path.join(REP_DIR, "token_report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(rep) + "\n")

    manifest = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "source": "手术麻醉信息系统_施工前方案书_V0.9.docx",
        "source_sha256_16": digest,
        "chapters": len(chapters),
        "chunks": len(chunks),
        "chars": total_chars,
        "tokens_cl100k": total_tokens,
        "structured_files": {k: v["file"] for k, v in st_meta.items()},
        "chapter_files": [f["file"] for f in ch_files],
        "full_md_tokens": full_tokens,
    }
    with open(os.path.join(OUT, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print(f"chapters: {len(chapters)}, chunks: {len(chunks)}, "
          f"chars: {total_chars}, tokens: {total_tokens}")
    print("structured:", ", ".join(f"{k}({len(v)})" for k, v in structured.items()))
    print("chapter files:", len(ch_files))


if __name__ == "__main__":
    main()
