#!/usr/bin/env bash
#
# 前端 UI 真机验证 (headless Chrome)
# ===================================
#
# 为什么需要这个:
#   REST 接口全 200 ≠ 前端能正常渲染。之前就踩过 —— 后端返回 JSON,
#   前端却写成 `row.commit()` (Java record 访问器的写法), 浏览器一渲染就
#   TypeError, 界面只显示"加载失败"。只测接口是发现不了的。
#
#   这个脚本用真的 Chrome 打开页面, 把渲染后的 DOM 抓下来, 检查:
#     - toast 有没有报错
#     - 分支 / 提交历史 / diff 有没有真的渲染出来
#     - 有没有 undefined 混进界面
#
# 用法:
#   ./scripts/verify-ui.sh [仓库路径]
#
set -euo pipefail

PORT=7788
REPO="${1:-/Users/niejq/projects/git-history-tool}"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DOM="/tmp/git-history-ui-$$.html"

if [ ! -x "$CHROME" ]; then
    echo "❌ 找不到 Chrome: $CHROME"
    exit 2
fi
if ! curl -sf --max-time 3 "http://localhost:$PORT/" >/dev/null 2>&1; then
    echo "❌ 服务没起, 先执行 ./start.sh"
    exit 2
fi

PROFILE="/tmp/chrome-ui-verify-$$"
"$CHROME" --headless=new --disable-gpu --no-first-run --no-default-browser-check \
    --user-data-dir="$PROFILE" --virtual-time-budget=12000 \
    --dump-dom "http://localhost:$PORT/?path=$REPO" > "$DOM" 2>/dev/null

python3 - "$DOM" <<'PY'
import re, sys

dom = open(sys.argv[1], encoding="utf-8").read()
problems = []

def text_of(pattern):
    m = re.search(pattern, dom, re.S)
    if not m:
        return None
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", m.group(1))).strip()

# toast: 出错时 class 会带 error
m = re.search(r'<div id="toast"[^>]*class="([^"]*)"[^>]*>(.*?)</div>', dom, re.S)
toast_class = m.group(1) if m else ""
toast_text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", m.group(2))).strip() if m else ""
print(f"  toast      : [{toast_class}] {toast_text}")
if "error" in toast_class:
    problems.append(f"页面报错: {toast_text}")

meta = text_of(r'<div class="repo-meta" id="repoMeta">(.*?)</div>')
n_branch = len(re.findall(r'<li class="branch-item', dom))
n_commit = len(re.findall(r'<li class="commit-item', dom))
n_tab = len(re.findall(r'<div class="file-tab', dom))
n_diff = len(re.findall(r'<div class="diff-line', dom))

print(f"  repoMeta   : {meta}")
print(f"  分支数     : {n_branch}")
print(f"  提交数     : {n_commit}")
print(f"  文件 tab 数: {n_tab}")
print(f"  diff 行数  : {n_diff}")

if n_commit == 0:
    problems.append("提交历史一条都没渲染出来")
if n_diff == 0:
    problems.append("diff 一行都没渲染出来")

# 找 undefined: 只能看"字段值"的位置 (sha / subject / meta / 分支名 / 仓库信息 / toast)。
# 不能全文搜 —— diff 面板里展示的文件内容本身就可能含 "undefined" 这个词
# (比如 README 里写了 "检查有没有 undefined 混进界面"), 那不是渲染 bug。
def undefined_in(pattern, label):
    hits = [m.group(1).strip() for m in re.finditer(pattern, dom, re.S)]
    bad = [h for h in hits if h in ("undefined", "null", "NaN")]
    if bad:
        problems.append(f"{label} 渲染成了 {bad[:3]}")

undefined_in(r'<span class="sha[^"]*">(.*?)</span>', "提交 SHA")
undefined_in(r'<span class="subject[^"]*">(.*?)</span>', "提交标题")
undefined_in(r'<span class="meta[^"]*">(.*?)</span>', "提交时间")
undefined_in(r'<span class="name[^"]*">(.*?)</span>', "分支名")
undefined_in(r'<div class="repo-meta" id="repoMeta">(.*?)</div>', "仓库信息")
undefined_in(r'<div id="toast"[^>]*>(.*?)</div>', "提示消息")

# DOM 属性级异常 (元素本身渲染成 undefined/null)
for m in re.finditer(r'<(span|div|li)[^>]*>\s*(undefined|null|NaN)\s*</\1>', dom):
    problems.append(f"有元素直接渲染成了 {m.group(2)}")

if problems:
    print("\n❌ UI 验证失败:")
    for p in problems:
        print("   -", p)
    sys.exit(1)

print("\n✅ UI 验证通过: 无报错, 分支/历史/diff 都渲染出来了")
PY

rm -f "$DOM"
