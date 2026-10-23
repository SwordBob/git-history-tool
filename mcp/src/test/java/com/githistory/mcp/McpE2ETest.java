package com.githistory.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 用 SDK 自带的 client 连自己的 server, 做端到端验证。
 *
 * <p>比手写 JSON-RPC 更接近真实 MCP client 的行为 (握手 / 协议版本协商 / 工具调用)。
 */
public class McpE2ETest {

    public static void main(String[] args) throws Exception {
        String repoPath = args.length > 0 ? args[0] : ".";
        String jarPath = args.length > 1 ? args[1] : "mcp/target/mcp-0.1.0-SNAPSHOT.jar";

        McpJsonMapper mapper = new JacksonMcpJsonMapper(JsonMapper.builder().build());

        ServerParameters params = ServerParameters.builder("java")
                .arg("-jar").arg(jarPath)
                .build();

        StdioClientTransport transport = new StdioClientTransport(params, mapper);
        McpSyncClient client = McpClient.sync(transport).build();

        System.out.println("========== 1. initialize ==========");
        McpSchema.InitializeResult init = client.initialize();
        System.out.println("  serverInfo = " + init.serverInfo());
        System.out.println("  protocolVersion = " + init.protocolVersion());
        System.out.println("  capabilities = " + init.capabilities());

        System.out.println("\n========== 2. tools/list ==========");
        McpSchema.ListToolsResult tools = client.listTools();
        System.out.println("  共 " + tools.tools().size() + " 个工具:");
        for (McpSchema.Tool t : tools.tools()) {
            List<String> props = List.of();
            if (t.inputSchema() != null && t.inputSchema().get("properties") instanceof Map<?, ?> pm) {
                props = pm.keySet().stream().map(String::valueOf).toList();
            }
            System.out.printf("    %-20s params=%s%n", t.name(), props);
            System.out.printf("        %s%n", t.description());
        }

        System.out.println("\n========== 3. tools/call git_list_branches ==========");
        call(client, "git_list_branches", Map.of("repo_path", repoPath));

        System.out.println("\n========== 4. tools/call git_history ==========");
        call(client, "git_history", Map.of("repo_path", repoPath, "limit", 5));

        System.out.println("\n========== 5. tools/call git_show_commit ==========");
        McpSchema.ListToolsResult ignored = null;
        call(client, "git_show_commit", Map.of("repo_path", repoPath, "commit_id", "HEAD"));

        System.out.println("\n========== 6. tools/call git_status ==========");
        call(client, "git_status", Map.of("repo_path", repoPath));

        System.out.println("\n========== 7. 错误处理 (不存在的仓库) ==========");
        call(client, "git_list_branches", Map.of("repo_path", "/definitely/not/a/repo"));

        System.out.println("\n✅ MCP 端到端全部通过");
        System.exit(0);
    }

    private static void call(McpSyncClient client, String tool, Map<String, Object> args) {
        try {
            McpSchema.CallToolResult result =
                    client.callTool(new McpSchema.CallToolRequest(tool, args));
            String text = result.content().stream()
                    .map(c -> c instanceof McpSchema.TextContent t ? t.text() : c.toString())
                    .reduce("", (a, b) -> a + b);
            System.out.println("  isError = " + result.isError());
            String[] lines = text.split("\n");
            for (int i = 0; i < Math.min(14, lines.length); i++) {
                System.out.println("    | " + lines[i]);
            }
            if (lines.length > 14) {
                System.out.println("    | ... (共 " + lines.length + " 行)");
            }
        } catch (Exception e) {
            System.out.println("  ❌ 调用异常: " + e);
        }
    }
}
