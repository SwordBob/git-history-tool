# git-history-tool

> **Git 历史浏览器** —— JGit 引擎 + Spring Boot 本地服务 + MCP tool
>
> 一套核心逻辑，两个前端：**给人看的浏览器界面**，和**给 LLM 调的 MCP 工具**。

```
┌─────────────────────────────────────────────────────────┐
│  core/     JGit 引擎（纯 Java, 不依赖 Spring, 不依赖 UI） │
│     ↑                          ↑                        │
│  server/   Spring Boot REST     mcp/    stdio MCP server │
│     ↓                          ↓                        │
│  浏览器界面               LLM / MCP client              │
└─────────────────────────────────────────────────────────┘
```

---

## 这是什么

一个能**看分支、看提交历史、看 diff、提交代码**的 Git 工具，范围刻意收窄：

| 功能 | 说明 |
|------|------|
| 分支列表 | 本地 + 远程，含当前分支标记、ahead/behind 提交数 |
| 提交历史 | 带 ASCII 提交图（`●` 普通提交 / `○` merge），按拓扑序 |
| 提交详情 | 作者、时间、完整信息、改了哪些文件（增删行数） |
| 行级 diff | unified diff 格式，行号准确，二进制文件单独标记 |
| 提交 | 勾选文件 → 暂存 → 提交 |

**明确不做**：合并冲突解决、rebase 界面、PR 管理、跨平台打包。这些是 Git GUI 里最贵的部分，
和"看历史"这个核心诉求无关。

---

## 为什么用 JGit 而不是调 git CLI

`JGit`（Eclipse 维护，7.8.0）是**纯 Java 实现的完整 Git**，直接操作对象模型：

- 不 spawn 进程、不解析 CLI 文本输出，没有转义/编码/换行的坑
- 读对象、index、refs、checkout、merge、rebase、ssh/http fetch & push 全都有
- 纯 Java → 一个 jar，天然跨平台，好分发

**JGit 的已知边界**（本项目未用到）：不支持 `git worktree`（多工作树）、
不支持 push signing、不支持 SHA-256 仓库。

### 踩坑记录：JGit 7.8 的 API 变化

写这个项目时被 JGit 7.x 的 API 改过好几轮，记下来免得下次再踩：

| 老 API (6.x) | JGit 7.8 |
|---|---|
| `RawTextFormatter` | **已移除**，改用 `new DiffFormatter(out)` + `format(DiffEntry)` |
| `RevSort.TOPO_ORDER` | `RevSort.TOPO` |
| `RefDatabase.getRefsByPrefix()` 返回 `Map` | 返回 `List<Ref>` |
| `Status.getConflicted()` | `Status.getConflicting()` |
| `Repository.getBranchConfig()` | `new BranchConfig(repo.getConfig(), name)` |
| `RevMergeBase` | 已移除（用 `markUninteresting` 算集合差即可） |
| `RevWalk.getRevCount()` | 已移除（遍历计数） |
| `RevCommit.getTree()` 对父提交返回 null | 父提交必须显式 `walk.parseCommit(id)` |
| `ObjectLoader` 不是 AutoCloseable | 不能用 try-with-resources |

另外：`FileRepositoryBuilder.setWorkTree(dir).findGitDir()` 之后 **`getGitDir()` 仍然是 null**，
但 `build()` 会自己补全 —— 别在 build 之前判断 null。

---

## 快速开始

### 1. 构建

```bash
mvn clean package
```

> 本机 Maven 走 `repo.maven.apache.org` 握手不稳的话, 在 `~/.m2/settings.xml` 配个镜像:
> ```xml
> <mirror>
>   <id>central-repo1</id>
>   <url>https://repo1.maven.org/maven2</url>
>   <mirrorOf>central</mirrorOf>
> </mirror>
> ```

### 2. 起 Web 界面

```bash
./start.sh
# 打开 http://localhost:7788
```

输入仓库路径 → 打开 → 左边选分支、中间点提交、右边看 diff、底部提交。

### 3. 当 MCP server 用

```bash
java -jar mcp/target/mcp-0.1.0-SNAPSHOT.jar
```

挂到 MCP client 上（比如 Claude Desktop / Mavis / Cursor）：

```json
{
  "mcpServers": {
    "git-history": {
      "command": "java",
      "args": ["-jar", "/绝对路径/git-history-tool/mcp/target/mcp-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

---

## MCP 工具清单

| 工具 | 参数 | 用途 |
|------|------|------|
| `git_list_branches` | `repo_path` | 列分支 + ahead/behind |
| `git_history` | `repo_path`, `ref?`, `limit?` | 提交历史（带提交图） |
| `git_show_commit` | `repo_path`, `commit_id` | 提交详情 + 改了哪些文件 |
| `git_diff` | `repo_path`, `from`, `to` | 两个提交之间的 unified diff |
| `git_file_at` | `repo_path`, `commit_id`, `file_path` | 某历史版本的文件内容 |
| `git_status` | `repo_path` | 工作区状态 |
| `git_commit` | `repo_path`, `message`, `paths?`, `stage_all?` | 暂存并提交 |

**为什么值得做成 MCP**：Git 历史分析对 LLM 来说是很自然的任务 ——
"这个方法这两年改过什么"、"这次改动引入了什么风险"。
给 LLM 一个能直接读仓库历史的工具，比让它猜强得多。

---

## REST 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/info?path=` | 仓库概览 |
| GET | `/api/branches?path=` | 分支列表 |
| GET | `/api/history?path=&ref=&limit=` | 提交历史 + 提交图 |
| GET | `/api/commit?path=&id=` | 提交详情 |
| GET | `/api/files?path=&commit=` | 某 commit 下的文件列表 |
| GET | `/api/changed?path=&commit=` | 某 commit 改了哪些文件 |
| GET | `/api/diff?path=&from=&to=` | 两个提交之间的 diff |
| GET | `/api/working-diff?path=` | 工作区未提交改动 |
| GET | `/api/status?path=` | 工作区状态 |
| GET | `/api/file?path=&commit=&file=` | 某 commit 下某文件内容 |
| POST | `/api/commit` | 提交 |

---

## 提交图算法

核心在 `HistoryService.buildGraph()`。维护一个 lane 数组，`lanes[i]` = 第 i 条泳道
当前"等待到达"的 commit id：

1. commit 来了，先看它在不在某个 lane 上（说明它是被别人引用的）
2. 不在 → 它是个分支尖端，开一条新 lane
3. 第一个父提交留在当前 lane，多出来的父提交（merge）在右边各开一条 lane
4. 根提交释放 lane

输出的 `GraphRow` 直接带 `prefix` / `marker` / `suffix` 三段字符串，
**前端和 MCP 都不需要理解 lane 逻辑**，拼起来就是图。

```
 ● 4da5ca17  fix(pom): Lombok 显式升到 1.18.42
 │● f152fe8f  fix: 修复编译冲突 + 一键启动脚本依赖失效
 │● 11d81a32  fix(guide): 估值流程准确版讲解
```

---

## 目录结构

```
git-history-tool/
├── core/                              # JGit 引擎, 不依赖 Spring / UI
│   └── com/githistory/core/
│       ├── GitHistoryCore.java        # 门面 (server 和 mcp 都只依赖它)
│       ├── RepositoryHolder.java      # 仓库缓存
│       ├── RepoService.java           # 仓库 / 分支 / 状态
│       ├── HistoryService.java        # 提交历史 + 提交图
│       ├── DiffService.java           # diff / 文件树
│       ├── CommitService.java         # 暂存 + 提交
│       ├── TimeUtil.java
│       └── model/                     # 10 个 record
├── server/                            # Spring Boot + 浏览器前端
│   └── resources/static/{index.html, app.js, style.css}
├── mcp/                               # stdio MCP server
│   └── com/githistory/mcp/McpServerMain.java
└── start.sh
```

---

## 技术栈

| | |
|---|---|
| Git 引擎 | JGit 7.8.0（EDL / BSD-3-Clause） |
| Web | Spring Boot 3.2.5 + 原生 JS（无前端构建） |
| MCP | MCP Java SDK 2.1.0（stdio） |
| JDK | 17 ~ 24（Lombok 1.18.42） |
| 端口 | 7788 |

---

## 已知边界

- 不支持 `git worktree`（JGit 限制）
- `git_commit` 依赖仓库已配置 `user.name` / `user.email`，没配会明确报错
- 二进制文件不做行级 diff，只标记 `[二进制]`
- MCP 模式是 **stdio**，只在本机用，不能远程访问
