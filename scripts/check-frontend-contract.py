#!/usr/bin/env python3
"""
前端 <-> API 契约检查
=====================

为什么要这个脚本
----------------
之前踩的坑: 后端返回的是 JSON, 前端却写成了 Java record 访问器的形式
(`row.commit()` 而不是 `row.commit`)。REST 接口全部 200, 但浏览器一渲染就
TypeError, 界面报 "加载失败"。

只测 API 返回 200 是发现不了这类问题的 —— 必须静态比对"前端怎么用字段"
和"后端真的返回了哪些字段"。

怎么用
------
1. 起服务: ./start.sh
2. 跑检查: python3 scripts/check-frontend-contract.py
3. 退出码 0 = 契约一致, 非 0 = 有问题
"""

import json
import re
import sys
import urllib.parse
import urllib.request

BASE = "http://localhost:7788"
REPO = "/Users/niejq/projects/git-history-tool"
APP_JS = "/Users/niejq/projects/git-history-tool/server/src/main/resources/static/app.js"

# 数组原型自带的成员 —— 不属于后端契约, 别误报
ARRAY_PROTO = {
    "length", "forEach", "map", "filter", "find", "findIndex", "some", "every",
    "reduce", "reduceRight", "slice", "splice", "join", "concat", "indexOf",
    "lastIndexOf", "includes", "push", "pop", "shift", "unshift", "sort",
    "reverse", "flat", "at", "keys", "values", "entries",
}


def fetch(path, params=None):
    url = BASE + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))


def collect_keys(obj, out):
    """递归收集 JSON 里出现过的所有 key"""
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.add(k)
            collect_keys(v, out)
    elif isinstance(obj, list):
        for v in obj:
            collect_keys(v, out)


def main():
    # ---- 1. 把后端真实返回的字段收齐 ----
    keys = set()
    endpoints = [
        ("/api/info", {"path": REPO}),
        ("/api/branches", {"path": REPO}),
        ("/api/history", {"path": REPO, "ref": "HEAD", "limit": 20}),
        ("/api/status", {"path": REPO}),
        ("/api/commit", {"path": REPO, "id": "HEAD"}),
        ("/api/changed", {"path": REPO, "commit": "HEAD"}),
        ("/api/files", {"path": REPO, "commit": "HEAD"}),
        ("/api/file", {"path": REPO, "commit": "HEAD", "file": "README.md"}),
        ("/api/working-diff", {"path": REPO}),
    ]
    for ep, params in endpoints:
        try:
            collect_keys(fetch(ep, params), keys)
        except Exception as e:
            print(f"❌ 调接口失败 {ep}: {e}")
            return 2

    print(f"✅ 后端返回字段共 {len(keys)} 个\n")

    # ---- 2. 扫前端 ----
    with open(APP_JS, encoding="utf-8") as f:
        js = f.read()

    problems = []

    # 2a. 【致命】后端字段被当方法调用
    #     后端返回的是 JSON, 字段永远是普通属性 —— 只要出现 `xxx.commit()` 这种
    #     写法就一定是 TypeError (界面直接报 "加载失败")。
    #     判定依据: 方法名同时是后端返回的字段名。
    for m in re.finditer(r"\.\s*([a-zA-Z_$][\w$]*)\s*\(", js):
        name = m.group(1)
        if name in keys:
            line = js[: m.start()].count("\n") + 1
            problems.append(
                (line, "CALL", name,
                 "后端返回的是 JSON, 字段是普通属性 —— 写 `obj.%s`, 不能写 `obj.%s()`" % (name, name))
            )

    # 2b. 数据变量上访问了后端没有的字段 (只查 api() 返回值直接赋值的变量)
    data_vars = set()
    for m in re.finditer(r"(?:const|let|var)\s+(\w+)\s*=\s*await\s+api\(", js):
        data_vars.add(m.group(1))
    for m in re.finditer(r"(?:const|let|var)\s+\[([^\]]+)\]\s*=\s*await\s+Promise\.all", js):
        for part in m.group(1).split(","):
            part = part.strip()
            if part:
                data_vars.add(part)

    for var in sorted(data_vars):
        for m in re.finditer(r"\b" + re.escape(var) + r"\.(\w+)", js):
            name = m.group(1)
            if name in keys or name in ARRAY_PROTO:
                continue
            line = js[: m.start()].count("\n") + 1
            problems.append(
                (line, "FIELD", f"{var}.{name}",
                 "后端 JSON 里没有这个字段 (拼错了? 或者它其实是 DOM 属性)")
            )

    if not problems:
        print("🎉 契约一致: 前端没有把 JSON 字段当方法调, 也没有引用不存在的后端字段")
        return 0

    print(f"❌ 发现 {len(problems)} 处问题:\n")
    for line, kind, name, why in problems:
        print(f"  app.js:{line:<5} [{kind:5}] {name:<24} {why}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
