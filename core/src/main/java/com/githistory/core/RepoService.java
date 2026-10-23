package com.githistory.core;

import com.githistory.core.model.BranchInfo;
import com.githistory.core.model.RepoInfo;
import com.githistory.core.model.StatusEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.BranchConfig;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库 / 分支 / 工作区状态
 *
 * <p>注意: 本项目用的是 JGit 7.8, 它的 API 跟 5.x/6.x 有出入
 * (getRefsByPrefix 返回 List 而非 Map, Status.getConflicting 取代 getConflicted 等)。
 */
public class RepoService {

    private final RepositoryHolder holder;

    public RepoService(RepositoryHolder holder) {
        this.holder = holder;
    }

    /** 仓库概览 */
    public RepoInfo info(String path) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        ObjectId head = repo.resolve(Constants.HEAD);
        List<String> remotes = new ArrayList<>();
        repo.getConfig().getSubsections("remote").forEach(remotes::add);
        return new RepoInfo(
                repo.getWorkTree().getAbsolutePath(),
                repo.getDirectory().getParentFile().getName(),
                holder.currentBranch(repo),
                head == null ? null : head.getName(),
                repo.isBare(),
                remotes);
    }

    /** 分支列表 (本地 + 远程), 排序: 当前分支 > 本地分支 > 远程分支, 再按提交时间倒序 */
    public List<BranchInfo> branches(String path) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        String current = holder.currentBranch(repo);

        List<Ref> all = new ArrayList<>();
        all.addAll(repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS));
        all.addAll(repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES));

        List<BranchInfo> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : all) {
                ObjectId objectId = peel(repo, ref);
                if (objectId == null) {
                    continue;
                }
                RevCommit tip = walk.parseCommit(objectId);
                String full = ref.getName();
                String shortName = full.substring(full.lastIndexOf('/') + 1);
                boolean remote = full.startsWith(Constants.R_REMOTES);

                long ahead = 0;
                long behind = 0;
                if (!remote) {
                    long[] ab = aheadBehind(repo, full);
                    ahead = ab[0];
                    behind = ab[1];
                }

                result.add(new BranchInfo(
                        shortName,
                        full,
                        tip.getName(),
                        tip.getName().substring(0, Math.min(8, tip.getName().length())),
                        tip.getAuthorIdent().getName(),
                        TimeUtil.relative(tip.getCommitTime() * 1000L),
                        tip.getCommitTime() * 1000L,
                        remote,
                        !remote && shortName.equals(current),
                        ahead,
                        behind));
            }
        }

        result.sort(Comparator
                .comparing((BranchInfo b) -> b.current() ? 0 : (b.remote() ? 2 : 1))
                .thenComparing(Comparator.comparingLong(BranchInfo::timestamp).reversed()));
        return result;
    }

    /** 工作区文件状态 */
    public List<StatusEntry> status(String path) throws IOException {
        Git git = holder.open(path);
        Status st;
        try {
            st = git.status().call();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException("读取工作区状态失败: " + e.getMessage(), e);
        }

        Map<String, StatusEntry> merged = new LinkedHashMap<>();
        // 已暂存
        st.getAdded().forEach(p -> merged.put(p, new StatusEntry(p, "ADDED", true)));
        st.getModified().forEach(p -> merged.put(p, new StatusEntry(p, "MODIFIED", true)));
        st.getRemoved().forEach(p -> merged.put(p, new StatusEntry(p, "DELETED", true)));
        // 未暂存
        st.getChanged().forEach(p -> merged.put(p, new StatusEntry(p, "MODIFIED", false)));
        st.getMissing().forEach(p -> merged.put(p, new StatusEntry(p, "DELETED", false)));
        // 未跟踪 / 冲突
        st.getUntracked().forEach(p -> merged.put(p, new StatusEntry(p, "UNTRACKED", false)));
        st.getConflicting().forEach(p -> merged.put(p, new StatusEntry(p, "CONFLICTED", false)));

        List<StatusEntry> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing(StatusEntry::status).thenComparing(StatusEntry::path));
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private ObjectId peel(Repository repo, Ref ref) throws IOException {
        if (ref.getObjectId() != null) {
            return ref.getObjectId();
        }
        // 符号引用 (如 refs/remotes/origin/HEAD) 需要 peel
        Ref peeled = repo.getRefDatabase().peel(ref);
        return peeled.getObjectId();
    }

    /**
     * 计算相对 upstream 的 ahead / behind 数量。
     *
     * <p>用集合差定义, 不需要 merge base:
     * ahead  = |commits(local) - commits(upstream)|  ← 你有它没有的
     * behind = |commits(upstream) - commits(local)|  ← 它有你没有的
     * RevWalk 里 markUninteresting(upstream) 之后遍历到的就是差集。
     */
    private long[] aheadBehind(Repository repo, String branchFullName) {
        try {
            BranchConfig cfg = new BranchConfig(repo.getConfig(), branchFullName);
            ObjectId upstreamId = resolveUpstream(repo, cfg);
            if (upstreamId == null) {
                return new long[]{0, 0};
            }
            ObjectId localId = repo.resolve(branchFullName);
            if (localId == null) {
                return new long[]{0, 0};
            }
            long ahead = countDifference(repo, localId, upstreamId);
            long behind = countDifference(repo, upstreamId, localId);
            return new long[]{ahead, behind};
        } catch (Exception e) {
            // ahead/behind 是锦上添花, 算不出来不该让整个列表挂掉
            return new long[]{0, 0};
        }
    }

    private ObjectId resolveUpstream(Repository repo, BranchConfig cfg) throws IOException {
        String[] candidates = {
                cfg.getRemoteTrackingBranch(),
                cfg.getTrackingBranch()
        };
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            ObjectId id = repo.resolve(c);
            if (id != null) {
                return id;
            }
            // "origin/main" 这种短名有时解析不了, 补全成完整 ref 再试
            id = repo.resolve(Constants.R_REMOTES + c);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /** |commits(from) - commits(exclude)| */
    private long countDifference(Repository repo, ObjectId from, ObjectId exclude) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRetainBody(false);
            walk.markStart(walk.parseCommit(from));
            walk.markUninteresting(walk.parseCommit(exclude));
            long count = 0;
            for (RevCommit ignored : walk) {
                count++;
            }
            return count;
        }
    }
}
