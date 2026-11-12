#!/usr/bin/env python3
# ---------------------------------------------------------------------------
# CI 依赖与许可证门禁（S0-04，由 .github/workflows/ci.yml 的 ci/deps-license 调用）
#
# 检查项：
#   1. 直接依赖白名单：解析所有 pom.xml 的 <dependencies>（不含
#      dependencyManagement）与各 package.json 的 dependencies/devDependencies，
#      每个外部直接依赖必须出现在 docs/deps/allowlist.md；
#      历史遗留缺口登记在 docs/ci/deps-allowlist-baseline.txt（带 expires，到期即失败），
#      新增依赖不在白名单即失败（AGENTS H-05）。
#   2. 许可证政策：allowlist 行内不得出现 AGPL/GPL-3.0（GPL-2.0 with CPE 的
#      Jakarta 标准 API 注解有明确登记，允许）；已安装 npm 包按
#      `pnpm licenses list --json` 的许可证分组检查，禁止 AGPL/GPL（LGPL 不在此列，
#      构建期工具由 allowlist 单独登记法务结论）。
#   3. 依赖漏洞：`pnpm audit --json` 中 high/critical 必须逐条登记在
#      docs/ci/audit-exceptions.md（含 GHSA、责任人、expires）；未登记或过期即失败。
#      生产依赖树另有 `pnpm audit --prod --audit-level high` 硬门禁（见 workflow）。
#
# 用法：
#   python3 docs/ci/check-deps-license.py \
#     --root . \
#     --npm-licenses .cache/npm-licenses.json \
#     --audit-json .cache/npm-audit-full.json
#
# 退出码：0 通过；1 存在违规；2 输入/解析错误。
# ---------------------------------------------------------------------------
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SKIP_DIRS = {
    ".git",
    ".m2repo",
    ".pnpm-store",
    ".pnpm-home",
    ".pnpm-cache",
    ".npm-cache",
    ".playwright",
    ".cache",
    ".toolchain",
    "node_modules",
    "target",
    "dist",
    "coverage",
}
INTERNAL_NPM_SCOPES = ("@anesop/",)
INTERNAL_MAVEN_GROUP = "com.anesop"

violations: list[str] = []
warnings: list[str] = []


def fail(msg: str) -> None:
    violations.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


# ---------------------------------------------------------------------------
# 1. 采集直接依赖
# ---------------------------------------------------------------------------
def iter_files(root: Path, name: str):
    for path in root.rglob(name):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        yield path


def maven_direct_deps(root: Path) -> set[str]:
    deps: set[str] = set()
    for pom in iter_files(root, "pom.xml"):
        try:
            # 输入是本仓库受版本控制的 pom.xml（非外部/用户输入），不存在 XXE 攻击面。
            # nosemgrep: python.lang.security.use-defused-xml-parse.use-defused-xml-parse
            tree = ET.parse(pom)
        except ET.ParseError as exc:  # 解析失败必须显式失败，不能静默跳过
            raise SystemExit(f"[deps] 无法解析 {pom}: {exc}")
        for node in tree.getroot().findall("./m:dependencies/m:dependency", NS):
            gid = (node.findtext("m:groupId", default="", namespaces=NS) or "").strip()
            aid = (node.findtext("m:artifactId", default="", namespaces=NS) or "").strip()
            if not aid:
                continue
            if "${" in gid or "${" in aid:
                warn(f"Maven 依赖坐标含未解析属性，已跳过：{pom} -> {gid}:{aid}")
                continue
            if gid == INTERNAL_MAVEN_GROUP:
                continue  # 项目内部模块，不是外部依赖
            deps.add(f"{gid}:{aid}")
    return deps


def npm_direct_deps(root: Path) -> set[str]:
    packages: list[Path] = [root / "package.json"]
    packages += sorted((root / "apps").glob("*/package.json"))
    packages += sorted((root / "packages").glob("*/package.json"))
    deps: set[str] = set()
    for pkg in packages:
        if not pkg.is_file():
            continue
        data = json.loads(pkg.read_text(encoding="utf-8"))
        for section in ("dependencies", "devDependencies", "optionalDependencies"):
            for name in (data.get(section) or {}):
                if name.startswith(INTERNAL_NPM_SCOPES):
                    continue  # workspace 内部包
                deps.add(name)
    return deps


# ---------------------------------------------------------------------------
# 2. allowlist 匹配
# ---------------------------------------------------------------------------
def maven_listed(group_id: str, artifact_id: str, allowlist: str) -> bool:
    if f"{group_id}:{artifact_id}" in allowlist:
        return True
    # 支持 "group:artifact-a / artifact-b" 这类合并单元格
    for line in allowlist.splitlines():
        if not line.lstrip().startswith("|"):
            continue
        cell = line.split("|")[1] if len(line.split("|")) > 1 else ""
        if f"{group_id}:" not in cell:
            continue
        rest = cell.split(":", 1)[1]
        if re.search(r"(?<![\w.-])" + re.escape(artifact_id) + r"(?![\w.-])", rest):
            return True
        if fnmatch.fnmatch(artifact_id, rest.strip()):
            return True
    return False


def npm_listed(name: str, allowlist: str) -> bool:
    return name in allowlist


def parse_baseline(path: Path) -> dict[str, str]:
    """历史遗留缺口：每行 `group:artifact  # expires=YYYY-MM-DD`。"""
    entries: dict[str, str] = {}
    if not path.is_file():
        return entries
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        # 只接受 "groupId:artifactId" 形式的条目，忽略标题/说明/引用行
        if not re.fullmatch(r"[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+", line):
            continue
        match = re.search(r"expires=(\d{4}-\d{2}-\d{2})", raw)
        if not match:
            raise SystemExit(f"[deps] {path}:{lineno} 缺少 expires=YYYY-MM-DD")
        entries[line] = match.group(1)
    return entries


def check_allowlist(
    root: Path, allowlist_path: Path, baseline_path: Path
) -> None:
    allowlist = allowlist_path.read_text(encoding="utf-8")
    baseline = parse_baseline(baseline_path)
    today = date.today().isoformat()

    maven_deps = maven_direct_deps(root)
    npm_deps = npm_direct_deps(root)

    for dep in sorted(maven_deps):
        group_id, artifact_id = dep.split(":", 1)
        if maven_listed(group_id, artifact_id, allowlist):
            continue
        if dep in baseline:
            if baseline[dep] < today:
                fail(f"白名单缺口登记已过期：{dep}（expires={baseline[dep]}），须补 docs/deps/allowlist.md")
            else:
                warn(f"白名单缺口（已登记至 {baseline[dep]}）：{dep}")
            continue
        fail(f"Maven 直接依赖不在白名单：{dep}（AGENTS H-05，须先写 ADR 并更新 docs/deps/allowlist.md）")

    for name in sorted(npm_deps):
        if npm_listed(name, allowlist):
            continue
        fail(f"npm 直接依赖不在白名单：{name}（AGENTS H-05，须先写 ADR 并更新 docs/deps/allowlist.md）")

    # 已补齐的 baseline 条目应删除，避免长期漂移
    for dep in baseline:
        if dep.startswith("@"):
            listed = npm_listed(dep, allowlist)
        else:
            group_id, _, artifact_id = dep.partition(":")
            listed = maven_listed(group_id, artifact_id, allowlist)
        if listed:
            warn(f"baseline 条目已进入白名单，可删除：{dep}")

    print(f"[deps] Maven 外部直接依赖 {len(maven_deps)} 个，npm 外部直接依赖 {len(npm_deps)} 个")


# ---------------------------------------------------------------------------
# 3. 许可证政策
# ---------------------------------------------------------------------------
FORBIDDEN_LICENSE = re.compile(r"(\bAGPL|\bGPL-3\.0|\bGPLv3)", re.IGNORECASE)


def check_allowlist_licenses(allowlist_path: Path) -> None:
    text = allowlist_path.read_text(encoding="utf-8")
    for lineno, line in enumerate(text.splitlines(), 1):
        if not line.lstrip().startswith("|"):
            continue
        for cell in line.split("|"):
            cell = cell.strip()
            if not cell:
                continue
            if "with CPE" in cell:
                continue  # Jakarta 标准 API 注解（GPL-2.0 with CPE）已在表中登记
            if FORBIDDEN_LICENSE.search(cell):
                fail(f"allowlist 第 {lineno} 行含禁止许可证：{cell}（GPL/AGPL 不得进入交付物）")


def check_npm_licenses(path: Path) -> None:
    if not path.is_file():
        return
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[license] 无法解析 {path}: {exc}")
    total = 0
    for license_expr, packages in data.items():
        names = [p.get("name", "?") for p in packages]
        total += len(names)
        if not license_expr or license_expr.lower() in ("unknown", "unlicensed"):
            fail(f"npm 包许可证未知：{', '.join(names[:10])}")
            continue
        if FORBIDDEN_LICENSE.search(license_expr):
            fail(f"npm 包许可证禁止：{license_expr} -> {', '.join(names[:10])}")
    print(f"[license] npm 已安装包 {total} 个，许可证分组 {len(data)} 类")


# ---------------------------------------------------------------------------
# 4. 审计例外
# ---------------------------------------------------------------------------
def parse_audit_exceptions(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    if not path.is_file():
        return entries
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.lstrip().startswith("|") or "GHSA-" not in raw:
            continue
        cells = [c.strip() for c in raw.split("|")]
        # 只有 high/critical 行才是阻断级例外；"记录项"（moderate/low）不需要 expires
        if not any(c.lower() in ("high", "critical") for c in cells):
            continue
        ids = re.findall(r"GHSA-[0-9a-z-]+", raw)
        match = re.search(r"expires=(\d{4}-\d{2}-\d{2})", raw)
        if not match:
            raise SystemExit(f"[audit] {path}:{lineno} 含 GHSA 但缺少 expires=YYYY-MM-DD")
        for ghsa in ids:
            entries[ghsa] = match.group(1)
    return entries


def check_audit(path: Path, exceptions_path: Path) -> None:
    if not path.is_file():
        warn(f"未提供审计 JSON（{path}），跳过漏洞例外检查")
        return
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"[audit] 无法解析 {path}: {exc}")

    advisories = data.get("advisories") or {}
    if isinstance(advisories, list):
        advisories = {str(i): v for i, v in enumerate(advisories)}
    exceptions = parse_audit_exceptions(exceptions_path)
    today = date.today().isoformat()

    counts: dict[str, int] = {}
    for adv in advisories.values():
        severity = (adv.get("severity") or "unknown").lower()
        counts[severity] = counts.get(severity, 0) + 1
        package = adv.get("module_name", "?")
        ghsa = adv.get("github_advisory_id") or adv.get("ghsa_id") or adv.get("id") or "?"
        title = (adv.get("title") or "").split("\n")[0][:80]
        if severity not in ("high", "critical"):
            print(f"[audit] {severity:<8} {package:<24} {ghsa}（低于阻断线，记录）")
            continue
        if ghsa not in exceptions:
            fail(f"未登记的 {severity} 漏洞：{package} {ghsa} — {title}")
            continue
        if exceptions[ghsa] < today:
            fail(f"审计例外已过期：{package} {ghsa}（expires={exceptions[ghsa]}），须修复或续期并评审")
            continue
        print(f"[audit] {severity:<8} {package:<24} {ghsa}（例外至 {exceptions[ghsa]}）")

    print(
        "[audit] 漏洞统计："
        + "，".join(f"{k}={v}" for k, v in sorted(counts.items()))
    )


# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(description="ANESOP CI 依赖与许可证门禁")
    parser.add_argument("--root", default=".", help="仓库根目录")
    parser.add_argument("--allowlist", default="docs/deps/allowlist.md")
    parser.add_argument(
        "--baseline", default="docs/ci/deps-allowlist-baseline.txt", help="历史遗留缺口登记"
    )
    parser.add_argument("--npm-licenses", default="", help="pnpm licenses list --json 输出")
    parser.add_argument("--audit-json", default="", help="pnpm audit --json 输出")
    parser.add_argument("--audit-exceptions", default="docs/ci/audit-exceptions.md")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    allowlist_path = root / args.allowlist
    if not allowlist_path.is_file():
        raise SystemExit(f"[deps] 缺少白名单文件：{allowlist_path}")

    check_allowlist(root, allowlist_path, root / args.baseline)
    check_allowlist_licenses(allowlist_path)
    if args.npm_licenses:
        check_npm_licenses(root / args.npm_licenses)
    else:
        warn("未提供 npm 许可证清单，跳过已安装包许可证检查")
    if args.audit_json:
        check_audit(root / args.audit_json, root / args.audit_exceptions)
    else:
        warn("未提供审计 JSON，跳过漏洞例外检查")

    print()
    for message in warnings:
        print(f"WARN  {message}")
    for message in violations:
        print(f"FAIL  {message}")
    if violations:
        print(f"\n[deps-license] FAILED：{len(violations)} 项违规")
        return 1
    print(f"\n[deps-license] OK（warning {len(warnings)} 项）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
