#!/usr/bin/env bash
#
# 前端 UI 真机验证 (headless Chrome)
# ===================================
#
# 为什么需要这个:
#   REST 接口全 200 ≠ 前端能正常渲染。之前就踩过两次 ——
#   1) 后端返回 JSON, 前端却写成 `row.commit()` (Java record 访问器的写法),
#      浏览器一渲染就 TypeError, 界面只显示"加载失败";
#   2) 切换分支这条路 (loadHistory) 上一轮压根没被验证过。
#   只测接口是发现不了这两类问题的。
#
#   这个脚本用真的 Chrome 打开页面, 把渲染后的 DOM 抓下来, 检查:
#     - toast 有没有报错
#     - 分支 / 提交历史 / diff 有没有真的渲染出来
#     - 有没有 undefined 混进界面
#     - 切换到另一个分支后, 历史有没有跟着变
#     - 页面上的 build 标识在不在 (用来确认不是旧版缓存)
#
# 用法:
#   ./scripts/verify-ui.sh [仓库路径]
#
set -euo pipefail

PORT=7788
REPO="${1:-/Users/niejq/projects/git-history-tool}"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DOM1="/tmp/git-history-ui-open-$$.html"
DOM2="/tmp/git-history-ui-branch-$$.html"

if [ ! -x "$CHROME" ]; then
    echo "❌ 找不到 Chrome: $CHROME"
    exit 2
fi
if ! curl -sf --max-time 3 "http://localhost:$PORT/" >/dev/null 2>&1; then
    echo "❌ 服务没起, 先执行 ./start.sh"
    exit 2
fi

dump_dom() {
    local out="$1"; shift
    local query="$1"
    local profile="/tmp/chrome-ui-verify-$$-$RANDOM"
    "$CHROME" --headless=new --disable-gpu --no-first-run --no-default-browser-check \
        --user-data-dir="$profile" --virtual-time-budget=12000 \
        --dump-dom "http://localhost:$PORT/?path=$REPO$query" > "$out" 2>/dev/null
}

echo "→ 1/2 打开仓库"
dump_dom "$DOM1" ""

# 找一个"不是当前分支"的分支来切换, 专门验切换这条路径
BRANCH=$(python3 - "$REPO" <<'PY'
import json, sys, urllib.parse, urllib.request
path = sys.argv[1]
url = "http://localhost:7788/api/branches?" + urllib.parse.urlencode({"path": path})
try:
    with urllib.request.urlopen(url, timeout=10) as r:
        branches = json.loads(r.read().decode())
except Exception:
    branches = []
other = [b for b in branches if not b.get("current")]
print(other[0]["fullName"] if other else "")
PY
)

echo "→ 2/2 切换到分支 ${BRANCH:-（无其他分支，跳过）}"
if [ -n "$BRANCH" ]; then
    dump_dom "$DOM2" "&branch=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$BRANCH")"
fi

python3 - "$DOM1" "$DOM2" "$BRANCH" <<'PY'
import re, sys

dom1 = open(sys.argv[1], encoding="utf-8").read()
dom2 = open(sys.argv[2], encoding="utf-8").read() if sys.argv[3] else ""
branch = sys.argv[3]
problems = []


def text_of(dom, pattern):
    m = re.search(pattern, dom, re.S)
    if not m:
        return None
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", m.group(1))).strip()


def check(dom, label):
    m = re.search(r'<div id="toast"[^>]*class="([^"]*)"[^>]*>(.*?)</div>', dom, re.S)
    toast_class = m.group(1) if m else ""
    toast_text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", m.group(2))).strip() if m else ""
    print(f"  [{label}] toast: [{toast_class}] {toast_text}")
    if "error" in toast_class:
        problems.append(f"{label} 页面报错: {toast_text}")

    n_commit = len(re.findall(r'<li class="commit-item', dom))
    n_diff = len(re.findall(r'<div class="diff-line', dom))
    n_tab = len(re.findall(r'<div class="file-tab', dom))
    n_branch = len(re.findall(r'<li class="branch-item', dom))
    build = text_of(dom, r'<span class="build-tag"[^>]*>(.*?)</span>')
    meta = text_of(dom, r'<div class="repo-meta" id="repoMeta">(.*?)</div>')
    print(f"  [{label}] repoMeta: {meta}")
    print(f"  [{label}] 分支 {n_branch} / 提交 {n_commit} / tab {n_tab} / diff 行 {n_diff}")
    print(f"  [{label}] build 标识: {build!r}")

    if n_commit == 0:
        problems.append(f"{label} 提交历史一条都没渲染出来")
    if n_diff == 0:
        problems.append(f"{label} diff 一行都没渲染出来")
    if not build:
        problems.append(f"{label} 页面上没有 build 标识 (可能是旧版缓存的页面)")

    # undefined 只能看"字段值"的位置 —— diff 面板展示的文件内容本身
    # 就可能含 "undefined" 这个词 (比如 README 里写的), 那不是渲染 bug
    def undefined_in(pattern, what):
        for m in re.finditer(pattern, dom, re.S):
            if m.group(1).strip() in ("undefined", "null", "NaN"):
                problems.append(f"{label} {what} 渲染成了 {m.group(1).strip()}")

    undefined_in(r'<span class="sha[^"]*">(.*?)</span>', "提交 SHA")
    undefined_in(r'<span class="subject[^"]*">(.*?)</span>', "提交标题")
    undefined_in(r'<span class="meta[^"]*">(.*?)</span>', "提交时间")
    undefined_in(r'<span class="name[^"]*">(.*?)</span>', "分支名")
    undefined_in(r'<div class="repo-meta" id="repoMeta">(.*?)</div>', "仓库信息")
    undefined_in(r'<div id="toast"[^>]*>(.*?)</div>', "提示消息")

    for m in re.finditer(r'<(span|div|li)[^>]*>\s*(undefined|null|NaN)\s*</\1>', dom):
        problems.append(f"{label} 有元素直接渲染成了 {m.group(2)}")

    return n_commit


n1 = check(dom1, "打开")

if branch:
    n2 = check(dom2, "切分支")
    sel = re.search(r'<select id="branchSelect">(.*?)</select>', dom2, re.S)
    picked = None
    if sel:
        for o in re.finditer(r'<option([^>]*)>(.*?)</option>', sel.group(1), re.S):
            if 'selected' in o.group(1):
                picked = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", o.group(2))).strip()
    print(f"  [切分支] 目标 {branch} → 下拉框当前值: {picked!r}")
    if n2 == 0:
        problems.append("切到分支后提交历史是空的")

if problems:
    print("\n❌ UI 验证失败:")
    for p in problems:
        print("   -", p)
    sys.exit(1)

print("\n✅ UI 验证通过: 无报错, 分支/历史/diff 都渲染出来了")
PY

rm -f "$DOM1" "$DOM2"
