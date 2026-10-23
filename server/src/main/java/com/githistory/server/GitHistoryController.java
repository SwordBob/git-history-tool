package com.githistory.server;

import com.githistory.core.GitHistoryCore;
import com.githistory.core.model.BranchInfo;
import com.githistory.core.model.CommitNode;
import com.githistory.core.model.CommitRequest;
import com.githistory.core.model.CommitResult;
import com.githistory.core.model.FileDiff;
import com.githistory.core.model.FileEntry;
import com.githistory.core.model.GraphRow;
import com.githistory.core.model.RepoInfo;
import com.githistory.core.model.StatusEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * REST 接口
 *
 * <p>所有接口都以 {@code path} 参数指定仓库目录 (可以是仓库内的子目录)。
 */
@RestController
@RequestMapping("/api")
public class GitHistoryController {

    private final GitHistoryCore core;

    public GitHistoryController(GitHistoryCore core) {
        this.core = core;
    }

    /** 仓库概览 */
    @GetMapping("/info")
    public RepoInfo info(@RequestParam String path) throws IOException {
        return core.info(path);
    }

    /** 分支列表 */
    @GetMapping("/branches")
    public List<BranchInfo> branches(@RequestParam String path) throws IOException {
        return core.branches(path);
    }

    /** 提交历史 + 提交图 */
    @GetMapping("/history")
    public List<GraphRow> history(@RequestParam String path,
                                  @RequestParam(defaultValue = "HEAD") String ref,
                                  @RequestParam(defaultValue = "200") int limit) throws IOException {
        return core.history(path, ref, limit);
    }

    /** 单个提交详情 */
    @GetMapping("/commit")
    public CommitNode commit(@RequestParam String path, @RequestParam String id) throws IOException {
        return core.commit(path, id);
    }

    /** 某个 commit 下的文件列表 */
    @GetMapping("/files")
    public List<FileEntry> files(@RequestParam String path, @RequestParam String commit)
            throws IOException {
        return core.filesAt(path, commit);
    }

    /** 某个 commit 改动了哪些文件 */
    @GetMapping("/changed")
    public List<FileDiff> changed(@RequestParam String path, @RequestParam String commit)
            throws IOException {
        return core.filesChangedIn(path, commit);
    }

    /** 两个 commit 之间的 diff */
    @GetMapping("/diff")
    public List<FileDiff> diff(@RequestParam String path,
                               @RequestParam String from,
                               @RequestParam String to) throws IOException {
        return core.diff(path, from, to);
    }

    /** 工作区未提交改动 */
    @GetMapping("/working-diff")
    public List<FileDiff> workingDiff(@RequestParam String path) throws IOException {
        return core.workingTreeDiff(path);
    }

    /** 工作区状态 */
    @GetMapping("/status")
    public List<StatusEntry> status(@RequestParam String path) throws IOException {
        return core.status(path);
    }

    /** 某个 commit 下某个文件的内容 */
    @GetMapping("/file")
    public FileContent file(@RequestParam String path,
                             @RequestParam String commit,
                             @RequestParam String file) throws IOException {
        return new FileContent(core.fileContent(path, commit, file));
    }

    /** 提交 */
    @PostMapping("/commit")
    public CommitResult commit(@RequestBody CommitBody body) throws IOException {
        return core.commit(body.path(), new CommitRequest(
                body.message(), body.paths(), body.stageAll()));
    }

    /**
     * 提交请求体
     *
     * @param paths    要加入暂存区的文件路径
     * @param stageAll 是否把全部改动 (含未跟踪) 都加入暂存区
     */
    public record CommitBody(String path, String message, List<String> paths, boolean stageAll) {
    }

    /** 文件内容响应 */
    public record FileContent(String content) {
    }
}
