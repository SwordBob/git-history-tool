package com.githistory.core;

import com.githistory.core.model.CommitRequest;
import com.githistory.core.model.CommitResult;
import com.githistory.core.model.FileDiff;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.List;

/**
 * 暂存 + 提交
 */
public class CommitService {

    private final RepositoryHolder holder;

    public CommitService(RepositoryHolder holder) {
        this.holder = holder;
    }

    /**
     * 暂存并提交。
     *
     * @param req.message  提交信息, 不能为空
     * @param req.paths    要加入暂存区的文件; 为空则只提交当前已暂存的内容
     * @param req.stageAll true = 先把所有改动 (含未跟踪文件) 全部加入暂存区
     */
    public CommitResult commit(String path, CommitRequest req) throws IOException {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("提交信息不能为空");
        }
        Git git = holder.open(path);
        Repository repo = git.getRepository();

        // 1. 暂存
        try {
            if (req.stageAll()) {
                git.add().addFilepattern(".").call();
            } else if (req.paths() != null && !req.paths().isEmpty()) {
                var add = git.add();
                for (String p : req.paths()) {
                    add.addFilepattern(p);
                }
                add.call();
            }
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException("暂存失败: " + e.getMessage(), e);
        }

        // 2. 作者信息: 优先用仓库配置。JGit 不会自动读 user.name/user.email,
        //    不显式 setAuthor 的话提交记录里作者是空的。
        StoredConfig cfg = repo.getConfig();
        String name = cfg.getString("user", null, "name");
        String email = cfg.getString("user", null, "email");
        if (name == null || name.isBlank() || email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "仓库未配置 user.name / user.email, 请先执行: git config user.name \"你的名字\" && git config user.email \"你的邮箱\"");
        }

        // 3. 提交
        RevCommit commit;
        try {
            commit = git.commit()
                    .setMessage(req.message())
                    .setAuthor(name, email)
                    .setCommitter(name, email)
                    .call();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException("提交失败: " + e.getMessage(), e);
        }

        // 4. 统计改了几个文件
        int changed = 0;
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit c = walk.parseCommit(commit.getId());
            RevCommit parent = c.getParentCount() > 0 ? c.getParent(0) : null;
            List<FileDiff> diffs = new DiffService(holder).filesChangedIn(path, commit.getName());
            changed = diffs.size();
        }

        String branch = holder.currentBranch(repo);
        return new CommitResult(
                commit.getName(),
                commit.getName().substring(0, Math.min(8, commit.getName().length())),
                commit.getFullMessage(),
                changed,
                branch);
    }
}
