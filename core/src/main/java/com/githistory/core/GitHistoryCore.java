package com.githistory.core;

import com.githistory.core.model.BranchInfo;
import com.githistory.core.model.CommitNode;
import com.githistory.core.model.CommitRequest;
import com.githistory.core.model.CommitResult;
import com.githistory.core.model.DiffLine;
import com.githistory.core.model.FileDiff;
import com.githistory.core.model.FileEntry;
import com.githistory.core.model.GraphRow;
import com.githistory.core.model.RepoInfo;
import com.githistory.core.model.StatusEntry;

import java.io.IOException;
import java.util.List;

/**
 * Git 历史浏览器的门面。
 *
 * <p>server (Spring Boot REST) 和 mcp (stdio MCP server) 都只依赖这个类,
 * 保证两条链路的行为完全一致 —— 界面上看到的和 LLM 调到的，是同一份逻辑。
 */
public class GitHistoryCore {

    private final RepositoryHolder holder = new RepositoryHolder();
    private final RepoService repoService = new RepoService(holder);
    private final HistoryService historyService = new HistoryService(holder);
    private final DiffService diffService = new DiffService(holder);
    private final CommitService commitService = new CommitService(holder);

    public RepositoryHolder holder() {
        return holder;
    }

    /** 仓库概览 */
    public RepoInfo info(String path) throws IOException {
        return repoService.info(path);
    }

    /** 分支列表 */
    public List<BranchInfo> branches(String path) throws IOException {
        return repoService.branches(path);
    }

    /** 工作区状态 */
    public List<StatusEntry> status(String path) throws IOException {
        return repoService.status(path);
    }

    /** 提交历史 + 提交图 */
    public List<GraphRow> history(String path, String ref, int limit) throws IOException {
        return historyService.history(path, ref, limit);
    }

    /** 单个提交详情 */
    public CommitNode commit(String path, String commitId) throws IOException {
        return historyService.commit(path, commitId);
    }

    /** 某个 commit 下的文件列表 */
    public List<FileEntry> filesAt(String path, String commitId) throws IOException {
        return diffService.filesAt(path, commitId);
    }

    /** 某个 commit 改动了哪些文件 */
    public List<FileDiff> filesChangedIn(String path, String commitId) throws IOException {
        return diffService.filesChangedIn(path, commitId);
    }

    /** 两个 commit 之间的 diff */
    public List<FileDiff> diff(String path, String fromCommit, String toCommit) throws IOException {
        return diffService.diff(path, fromCommit, toCommit);
    }

    /** 工作区未提交改动 */
    public List<FileDiff> workingTreeDiff(String path) throws IOException {
        return diffService.workingTreeDiff(path);
    }

    /** 某个 commit 下某个文件的内容 */
    public String fileContent(String path, String commitId, String filePath) throws IOException {
        return diffService.fileContent(path, commitId, filePath);
    }

    /** 暂存并提交 */
    public CommitResult commit(String path, CommitRequest req) throws IOException {
        return commitService.commit(path, req);
    }

    /**
     * 把 diff 渲染成纯文本 (给 CLI / MCP 用)。
     *
     * <p>格式跟 git diff 接近, 方便人和 LLM 都好读。
     */
    public String renderDiff(List<FileDiff> diffs) {
        StringBuilder sb = new StringBuilder();
        for (FileDiff d : diffs) {
            sb.append("--- a/").append(d.oldPath()).append('\n');
            sb.append("+++ b/").append(d.path()).append('\n');
            sb.append("@@ ").append(d.changeType()).append(' ')
                    .append('+').append(d.additions()).append(" -").append(d.deletions()).append(" @@\n");
            for (DiffLine l : d.lines()) {
                sb.append(l.origin()).append(l.content()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
