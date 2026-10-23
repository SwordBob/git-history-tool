package com.githistory.core;

import com.githistory.core.model.CommitNode;
import com.githistory.core.model.GraphRow;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提交历史 + 提交图 (lane 布局)
 */
public class HistoryService {

    private final RepositoryHolder holder;

    public HistoryService(RepositoryHolder holder) {
        this.holder = holder;
    }

    /**
     * 取某个 ref 的提交历史, 并渲染成带 lane 的提交图。
     *
     * @param ref   分支名 / 远端分支名 / tag / commit id / "HEAD"
     * @param limit 最多返回多少个 commit
     */
    public List<GraphRow> history(String path, String ref, int limit) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        ObjectId startId = resolve(repo, ref);
        if (startId == null) {
            return List.of();
        }
        int max = Math.max(1, Math.min(limit, 5000));

        List<CommitNode> nodes = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            // 拓扑序 = git log --graph 的默认顺序: 父提交永远排在子提交后面
            walk.sort(RevSort.TOPO);
            walk.markStart(walk.parseCommit(startId));
            for (RevCommit c : walk) {
                if (nodes.size() >= max) {
                    break;
                }
                nodes.add(toNode(c, List.of()));
            }
        }

        Map<String, List<String>> labels = branchLabels(repo);
        List<CommitNode> labeled = nodes.stream()
                .map(n -> withLabels(n, labels))
                .toList();
        return buildGraph(labeled);
    }

    /** 单个提交的详情 (不带图) */
    public CommitNode commit(String path, String commitId) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        ObjectId id = repo.resolve(commitId);
        if (id == null) {
            id = ObjectId.fromString(commitId);
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit c = walk.parseCommit(id);
            return withLabels(toNode(c, List.of()), branchLabels(repo));
        }
    }

    // ------------------------------------------------------------------ 图布局

    /**
     * lane 布局算法。
     *
     * <p>lanes[i] = 第 i 条泳道当前"等待到达"的 commit id, null 表示空闲。
     * 每来一个 commit:
     * <ol>
     *   <li>如果没在任何 lane 上 (分支尖端), 开一条新 lane</li>
     *   <li>第一个父提交留在当前 lane</li>
     *   <li>其余父提交 (merge) 在右边各开一条 lane</li>
     * </ol>
     * 前端只需要把 prefix + marker + suffix 拼起来, 不用理解 lane 逻辑。
     */
    private List<GraphRow> buildGraph(List<CommitNode> commits) {
        List<String> lanes = new ArrayList<>();
        List<GraphRow> rows = new ArrayList<>();

        for (CommitNode node : commits) {
            int idx = lanes.indexOf(node.id());
            boolean hasLineAbove = true;
            if (idx < 0) {
                idx = firstFree(lanes);
                if (idx < 0) {
                    lanes.add(null);
                    idx = lanes.size() - 1;
                }
                hasLineAbove = false;
            }

            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < lanes.size(); i++) {
                if (i == idx) {
                    prefix.append(hasLineAbove ? '│' : ' ');
                } else {
                    prefix.append(lanes.get(i) != null ? '│' : ' ');
                }
            }

            // merge 的额外父提交 -> 右侧新 lane
            List<Integer> extraLanes = new ArrayList<>();
            List<String> parents = node.parents();
            for (int p = 1; p < parents.size(); p++) {
                int nl = firstFree(lanes);
                if (nl < 0) {
                    lanes.add(null);
                    nl = lanes.size() - 1;
                }
                lanes.set(nl, parents.get(p));
                extraLanes.add(nl);
            }

            StringBuilder suffix = new StringBuilder();
            for (int i = idx + 1; i < lanes.size(); i++) {
                if (extraLanes.contains(i)) {
                    suffix.append('┐');
                } else {
                    suffix.append(lanes.get(i) != null ? '│' : ' ');
                }
            }

            // 当前 lane 继续指向第一个父提交; 根提交则释放这条 lane
            lanes.set(idx, parents.isEmpty() ? null : parents.get(0));

            String marker = parents.size() > 1 ? "○" : "●";
            rows.add(new GraphRow(prefix.toString(), marker, suffix.toString(), node));
        }
        return rows;
    }

    private int firstFree(List<String> lanes) {
        for (int i = 0; i < lanes.size(); i++) {
            if (lanes.get(i) == null) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ 内部

    private ObjectId resolve(Repository repo, String ref) throws IOException {
        if (ref == null || ref.isBlank() || Constants.HEAD.equals(ref)) {
            return repo.resolve(Constants.HEAD);
        }
        ObjectId id = repo.resolve(ref);
        if (id == null) {
            try {
                id = ObjectId.fromString(ref);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return id;
    }

    private CommitNode toNode(RevCommit c, List<String> labels) {
        String id = c.getName();
        var author = c.getAuthorIdent();
        List<String> parents = new ArrayList<>();
        for (RevCommit p : c.getParents()) {
            parents.add(p.getName());
        }
        return new CommitNode(
                id,
                id.substring(0, Math.min(8, id.length())),
                author.getName(),
                author.getEmailAddress(),
                TimeUtil.relative(c.getCommitTime() * 1000L),
                c.getCommitTime() * 1000L,
                c.getShortMessage(),
                c.getFullMessage(),
                parents,
                labels);
    }

    private CommitNode withLabels(CommitNode node, Map<String, List<String>> labels) {
        List<String> l = labels.getOrDefault(node.id(), List.of());
        if (l.isEmpty()) {
            return node;
        }
        return new CommitNode(node.id(), node.shortId(), node.author(), node.email(),
                node.relativeTime(), node.timestamp(), node.subject(), node.message(),
                node.parents(), l);
    }

    /** commit id -> 指向它的分支/tag 名 */
    private Map<String, List<String>> branchLabels(Repository repo) throws IOException {
        Map<String, List<String>> labels = new HashMap<>();
        collect(repo, Constants.R_HEADS, "refs/heads/", labels);
        collect(repo, Constants.R_REMOTES, "refs/remotes/", labels);
        collect(repo, Constants.R_TAGS, "refs/tags/", labels);
        return labels;
    }

    private void collect(Repository repo, String prefix, String strip,
                          Map<String, List<String>> labels) throws IOException {
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix(prefix)) {
            ObjectId id = ref.getObjectId();
            if (id == null) {
                Ref peeled = repo.getRefDatabase().peel(ref);
                id = peeled.getObjectId();
            }
            if (id == null) {
                continue;
            }
            String name = ref.getName();
            if (name.startsWith(strip)) {
                name = name.substring(strip.length());
            }
            labels.computeIfAbsent(id.getName(), k -> new ArrayList<>()).add(name);
        }
    }
}
