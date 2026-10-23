package com.githistory.mcp;

import com.githistory.core.GitHistoryCore;
import com.githistory.core.model.BranchInfo;
import com.githistory.core.model.CommitNode;
import com.githistory.core.model.CommitRequest;
import com.githistory.core.model.CommitResult;
import com.githistory.core.model.FileDiff;
import com.githistory.core.model.FileEntry;
import com.githistory.core.model.GraphRow;
import com.githistory.core.model.StatusEntry;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * stdio MCP server —— 把 GitHistoryCore 暴露成 LLM 可调用的工具。
 *
 * <p>和 Web UI 共用同一套 core, 所以"界面上看到的"和"LLM 调到的"是同一份逻辑。
 *
 * <p>暴露的工具:
 * <ul>
 *   <li>git_list_branches  列出分支</li>
 *   <li>git_history        提交历史 (带提交图)</li>
 *   <li>git_show_commit    单个提交的详情 + 改了哪些文件</li>
 *   <li>git_diff           两个提交之间的 diff</li>
 *   <li>git_file_at        某个提交下某个文件的内容</li>
 *   <li>git_status         工作区状态</li>
 *   <li>git_commit         暂存并提交</li>
 * </ul>
 */
public class McpServerMain {

    private static final String SERVER_NAME = "git-history-tool";
    private static final String SERVER_VERSION = "0.1.0";

    private final GitHistoryCore core = new GitHistoryCore();

    public static void main(String[] args) {
        new McpServerMain().run();
    }

    public void run() {
        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        // 等客户端断开 (stdin EOF) 再优雅关闭。
        // McpSyncServer.closeGracefully() 是 void —— 调了立刻就关, 所以不能直接调。
        CountDownLatch stdinEof = new CountDownLatch(1);
        InputStream stdin = new EofAwareInputStream(System.in, stdinEof::countDown);
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(mapper, stdin, System.out);

        McpServer.SyncSpecification<?> spec = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("""
                        Git 历史分析工具。基于 JGit 纯 Java 实现, 不依赖本机 git 命令。
                        典型用法:
                        1) git_list_branches 看有哪些分支
                        2) git_history 看某个分支的提交历史
                        3) git_show_commit / git_diff 看某次提交改了什么
                        4) git_file_at 看某个历史版本的文件内容
                        """)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());

        register(spec, "git_list_branches",
                "列出仓库的所有分支 (本地 + 远程), 含当前分支、领先/落后提交数。",
                schema(Map.of("repo_path", stringProp("仓库目录路径, 可以是仓库内的子目录"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    List<BranchInfo> branches = core.branches(path);
                    StringBuilder sb = new StringBuilder();
                    sb.append("共 ").append(branches.size()).append(" 个分支:\n");
                    for (BranchInfo b : branches) {
                        sb.append(String.format("  %-30s %s  %-16s %s%s%s%n",
                                b.name(),
                                b.shortCommitId(),
                                b.remote() ? "[remote]" : "[local]",
                                b.relativeTime(),
                                b.current() ? "  <== 当前分支" : "",
                                (b.ahead() > 0 || b.behind() > 0)
                                        ? String.format("  (ahead %d / behind %d)", b.ahead(), b.behind())
                                        : ""));
                    }
                    return ok(sb.toString());
                });

        register(spec, "git_history",
                "查看提交历史, 带 ASCII 提交图 (● 普通提交, ○ merge)。按拓扑序返回。",
                schema(List.of("repo_path"),
                        Map.of("repo_path", stringProp("仓库目录路径")),
                        Map.of("ref", stringProp("分支名 / 远端分支 / tag / commit id, 默认 HEAD")),
                        Map.of("limit", Map.of("type", "integer", "description", "最多返回多少个提交, 默认 50"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    String ref = str(request, "ref");
                    if (ref == null || ref.isBlank()) {
                        ref = "HEAD";
                    }
                    int limit = intArg(request, "limit", 50);
                    List<GraphRow> rows = core.history(path, ref, limit);
                    StringBuilder sb = new StringBuilder();
                    sb.append("ref=").append(ref).append(", 共 ").append(rows.size()).append(" 个提交:\n\n");
                    for (GraphRow row : rows) {
                        CommitNode c = row.commit();
                        sb.append(String.format("%s %s  %-20s  %s%n",
                                row.render(), c.shortId(), c.relativeTime(), c.subject()));
                        if (!c.branchLabels().isEmpty()) {
                            sb.append("      分支: ").append(String.join(", ", c.branchLabels())).append('\n');
                        }
                    }
                    return ok(sb.toString());
                });

        register(spec, "git_show_commit",
                "查看单个提交的详情: 作者、时间、完整信息、改了哪些文件 (含增删行数)。",
                schema(List.of("repo_path", "commit_id"),
                        Map.of("repo_path", stringProp("仓库目录路径")),
                        Map.of("commit_id", stringProp("commit id (可短可长), 或分支名/tag"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    String id = str(request, "commit_id");
                    CommitNode c = core.commit(path, id);
                    List<FileDiff> changed = core.filesChangedIn(path, c.id());
                    StringBuilder sb = new StringBuilder();
                    sb.append("commit ").append(c.id()).append('\n');
                    sb.append("作者: ").append(c.author()).append(" <").append(c.email()).append(">\n");
                    sb.append("时间: ").append(c.relativeTime()).append('\n');
                    sb.append("父提交: ")
                            .append(c.parents().isEmpty() ? "(根提交)" : String.join(", ", c.parents()))
                            .append('\n');
                    sb.append("信息:\n").append(c.message()).append('\n');
                    sb.append("改动 ").append(changed.size()).append(" 个文件:\n");
                    for (FileDiff d : changed) {
                        sb.append(String.format("  %s %-60s +%d -%d%s%n",
                                d.changeType(), d.path(), d.additions(), d.deletions(),
                                d.binary() ? "  [二进制]" : ""));
                    }
                    return ok(sb.toString());
                });

        register(spec, "git_diff",
                "两个提交之间的 diff, 返回 unified diff 格式的文本。",
                schema(List.of("repo_path", "from", "to"),
                        Map.of("repo_path", stringProp("仓库目录路径")),
                        Map.of("from", stringProp("起点 commit id / 分支名")),
                        Map.of("to", stringProp("终点 commit id / 分支名"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    List<FileDiff> diffs = core.diff(path, str(request, "from"), str(request, "to"));
                    if (diffs.isEmpty()) {
                        return ok("两个提交之间没有差异。");
                    }
                    return ok(core.renderDiff(diffs));
                });

        register(spec, "git_file_at",
                "读取某个提交下某个文件的内容, 适合看这个文件以前长什么样。",
                schema(List.of("repo_path", "commit_id", "file_path"),
                        Map.of("repo_path", stringProp("仓库目录路径")),
                        Map.of("commit_id", stringProp("commit id 或分支名")),
                        Map.of("file_path", stringProp("仓库内的文件相对路径, 如 src/main/java/A.java"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    String content = core.fileContent(path, str(request, "commit_id"), str(request, "file_path"));
                    if (content.isEmpty()) {
                        return ok("文件不存在或内容为空。");
                    }
                    return ok(content);
                });

        register(spec, "git_status",
                "查看工作区状态: 哪些文件改了、哪些已暂存、哪些未跟踪。",
                schema(Map.of("repo_path", stringProp("仓库目录路径"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    List<StatusEntry> status = core.status(path);
                    if (status.isEmpty()) {
                        return ok("工作区干净, 没有未提交改动。");
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("共 ").append(status.size()).append(" 个文件有改动:\n");
                    for (StatusEntry s : status) {
                        sb.append(String.format("  %-10s %-8s %s%n",
                                s.status(), s.staged() ? "已暂存" : "未暂存", s.path()));
                    }
                    return ok(sb.toString());
                });

        register(spec, "git_commit",
                "暂存并提交。paths 为空且 stage_all=false 时只提交已暂存的内容。",
                schema(List.of("repo_path", "message"),
                        Map.of("repo_path", stringProp("仓库目录路径")),
                        Map.of("message", stringProp("提交信息, 必填")),
                        Map.of("paths", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "要加入暂存区的文件路径列表")),
                        Map.of("stage_all", Map.of("type", "boolean",
                                "description", "是否把所有改动(含未跟踪文件)都暂存, 默认 false"))),
                (request) -> {
                    String path = str(request, "repo_path");
                    String message = str(request, "message");
                    List<String> paths = stringList(request, "paths");
                    boolean stageAll = boolArg(request, "stage_all", false);
                    CommitResult r = core.commit(path, new CommitRequest(message, paths, stageAll));
                    return ok(String.format("提交成功: %s%n信息: %s%n改动文件数: %d%n分支: %s",
                            r.commitId(), r.message(), r.filesChanged(), r.branch()));
                });

        McpSyncServer server = spec.build();
        System.err.println("[git-history-tool] MCP server 已启动 (stdio), 工具: 7 个");

        // 阻塞主线程, 直到客户端关闭 stdin
        try {
            stdinEof.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.err.println("[git-history-tool] 客户端已断开, 正在关闭...");
        server.getAsyncServer().closeGracefully().block();
    }

    /**
     * 带 EOF 感知的 InputStream。
     *
     * <p>SDK 的 stdio 传输会一直读 System.in, 外面没人知道客户端什么时候断开。
     * 这里包一层, 在读到 EOF (-1) 时回调, 主线程就能知道该收工了。
     */
    static final class EofAwareInputStream extends FilterInputStream {

        private final Runnable onEof;
        private boolean signalled;

        EofAwareInputStream(InputStream in, Runnable onEof) {
            super(in);
            this.onEof = onEof;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            checkEof(b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            checkEof(n);
            return n;
        }

        private void checkEof(int result) {
            if (result == -1 && !signalled) {
                signalled = true;
                onEof.run();
            }
        }
    }

    // ------------------------------------------------------------------ 工具注册

    private void register(McpServer.SyncSpecification<?> spec,
                          String name,
                          String description,
                          Map<String, Object> inputSchema,
                          ToolHandler handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        spec.toolCall(tool, (exchange, request) -> {
            try {
                return handler.apply(request);
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent("调用失败: " + e.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    /**
     * 工具处理函数。允许抛受检异常 (core 的方法都抛 IOException),
     * 统一在 register 里包成 isError 的结果。
     */
    @FunctionalInterface
    interface ToolHandler {
        McpSchema.CallToolResult apply(McpSchema.CallToolRequest request) throws Exception;
    }

    // ------------------------------------------------------------------ 参数辅助

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** 构造 JSON schema: 所有参数都必填 */
    private static Map<String, Object> schema(Map<String, Object>... props) {
        return schema(List.of(), props);
    }

    /**
     * 构造 JSON schema。
     *
     * @param required 必填参数名; 其余参数都是可选的
     */
    private static Map<String, Object> schema(List<String> required, Map<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> p : props) {
            properties.put(p.keySet().iterator().next(), p.values().iterator().next());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> args(McpSchema.CallToolRequest request) {
        Map<String, Object> a = request.arguments();
        return a == null ? Map.of() : a;
    }

    private static String str(McpSchema.CallToolRequest request, String key) {
        Object v = args(request).get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intArg(McpSchema.CallToolRequest request, String key, int def) {
        Object v = args(request).get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean boolArg(McpSchema.CallToolRequest request, String key, boolean def) {
        Object v = args(request).get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(McpSchema.CallToolRequest request, String key) {
        Object v = args(request).get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return List.of();
    }

    private static McpSchema.CallToolResult ok(String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .isError(false)
                .build();
    }
}
