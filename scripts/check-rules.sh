#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 规则一致性检查（S0-03）
#  1) 适配层漂移：CLAUDE.md、.cursor/rules/、.github/copilot-instructions.md、
#     .github/instructions/ 只允许"指向 AGENTS.md 的指针"，不得含规则正文。
#     判定用关键词启发式（输出具体文件:行号），非法律级精确。
#  2) 临时条款过期：扫描 TODO(TEMP / TEMP: 中 expires=YYYY-MM-DD 且已过期的行。
# 无问题输出 OK 并退出 0；有漂移/过期退出 1。
# ---------------------------------------------------------------------------
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0
today="$(date +%Y-%m-%d)"

hr() { printf '%s\n' "------------------------------------------------------------"; }

# ---------------------------------------------------------------------------
# 1. 适配层漂移
# ---------------------------------------------------------------------------
adapter_files=()
[ -f CLAUDE.md ] && adapter_files+=("CLAUDE.md")
[ -f .github/copilot-instructions.md ] && adapter_files+=(".github/copilot-instructions.md")
if [ -d .cursor/rules ]; then
  while IFS= read -r f; do adapter_files+=("$f"); done < <(find .cursor/rules -type f | sort)
fi
if [ -d .github/instructions ]; then
  while IFS= read -r f; do adapter_files+=("$f"); done < <(find .github/instructions -type f | sort)
fi

# 规则正文关键词（中文 + 英文）；出现在"非指针行"即视为疑似规则正文
rule_keyword='(必须|禁止|不得|严禁|应当|只允许|一律|must|shall|should|forbidden|prohibited|required|mandatory)'

if [ ${#adapter_files[@]} -eq 0 ]; then
  echo "[1/2] 适配层漂移：未发现适配层文件（CLAUDE.md / .cursor/rules / .github/copilot-instructions.md / .github/instructions），跳过"
else
  echo "[1/2] 适配层漂移：检查 ${#adapter_files[@]} 个文件"
  for f in "${adapter_files[@]}"; do
    if ! grep -q 'AGENTS\.md' "$f"; then
      echo "  DRIFT: $f 未指向 AGENTS.md（适配层必须只做指针）"
      fail=1
      continue
    fi
    # 逐行检查：跳过空行、标题行、仅含 AGENTS.md 指针的行、注释/分隔行
    while IFS= read -r hit; do
      [ -z "$hit" ] && continue
      lineno="${hit%%:*}"
      text="${hit#*:}"
      printf '  DRIFT: %s:%s 疑似规则正文（应改为指向 AGENTS.md 的指针）：%s\n' \
        "$f" "$lineno" "$(printf '%s' "$text" | cut -c1-100)"
      fail=1
    done < <(grep -nE "$rule_keyword" "$f" \
      | grep -v 'AGENTS\.md' \
      | grep -vE '^[0-9]+:[[:space:]]*(#|//|<!--|-->)' \
      | grep -vE '^[0-9]+:[[:space:]]*$')
  done
fi

hr

# ---------------------------------------------------------------------------
# 2. 临时条款过期
# ---------------------------------------------------------------------------
echo "[2/2] 临时条款过期：扫描 TODO(TEMP / TEMP: 的 expires=YYYY-MM-DD"
# 直接扫工作区（含未提交改动），排除依赖/缓存/构建产物/方案书原件
temp_hits=""
while IFS= read -r -d '' f; do
  if hits="$(grep -nIE 'TODO\(TEMP|TEMP:' "$f")"; then
    while IFS= read -r line; do
      temp_hits+="${f}:${line}"$'\n'
    done <<< "$hits"
  else
    rc=$?
    if [ "$rc" -ne 1 ]; then
      echo "check-rules.sh: 扫描 $f 失败（grep exit=$rc）" >&2
      exit 2
    fi
  fi
done < <(
  find . \
    \( -type d \( -name .git -o -name node_modules -o -name target -o -name dist \
    -o -name coverage -o -name .m2repo -o -name .toolchain -o -name '.pnpm-*' \
    -o -name .npm-cache -o -name .playwright -o -name .cache -o -path ./docs/plan \) -prune \) \
    -o \( -type f \( -name pnpm-lock.yaml -o -name '*.jar' -o -name '*.png' -o -name '*.pdf' \
    -o -name '*.docx' -o -path ./scripts/check-rules.sh \) -prune \) \
    -o -type f -print0 2>/dev/null
)

expired=0
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  file="${hit%%:*}"
  rest="${hit#*:}"
  lineno="${rest%%:*}"
  text="${rest#*:}"
  if [[ "$text" =~ expires=([0-9]{4}-[0-9]{2}-[0-9]{2}) ]]; then
    exp="${BASH_REMATCH[1]}"
    if [[ "$exp" < "$today" ]]; then
      printf '  EXPIRED: %s:%s 临时条款已过期（expires=%s，今天 %s）\n' "$file" "$lineno" "$exp" "$today"
      fail=1
      expired=$((expired + 1))
    fi
  else
    printf '  WARN: %s:%s 临时条款缺少 expires=YYYY-MM-DD（不阻断，请补齐）\n' "$file" "$lineno"
  fi
done <<< "$temp_hits"

if [ "$expired" -eq 0 ]; then
  echo "  未发现已过期的临时条款"
fi

hr
if [ "$fail" -ne 0 ]; then
  echo "check-rules.sh: FAILED（存在适配层漂移或过期的临时条款，见上方文件:行号）" >&2
  exit 1
fi
echo "check-rules.sh: OK"
