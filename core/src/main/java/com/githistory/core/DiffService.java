package com.githistory.core;

import com.githistory.core.model.DiffLine;
import com.githistory.core.model.FileDiff;
import com.githistory.core.model.FileEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * diff 与文件树
 *
 * <p>JGit 7.x 里 {@code RawTextFormatter} 已被移除, 现在统一走
 * {@code new DiffFormatter(OutputStream)} + {@code format(DiffEntry)},
 * 拿到标准 unified diff 文本再解析成行级结构。
 */
public class DiffService {

    private final RepositoryHolder holder;

    public DiffService(RepositoryHolder holder) {
        this.holder = holder;
    }

    /** 某个 commit 下的完整文件列表 */
    public List<FileEntry> filesAt(String path, String commitId) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(resolve(repo, commitId));
            List<FileEntry> out = new ArrayList<>();
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    ObjectId id = tw.getObjectId(0);
                    long size = 0;
                    boolean binary = false;
                    ObjectLoader loader = repo.open(id, Constants.OBJ_BLOB);
                    size = loader.getSize();
                    binary = isBinary(loader);
                    out.add(new FileEntry(tw.getPathString(), size, binary,
                            Integer.toOctalString(tw.getRawMode(0))));
                }
            }
            return out;
        }
    }

    /** 某个 commit 改动了哪些文件 (相对它的第一个父提交) */
    public List<FileDiff> filesChangedIn(String path, String commitId) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(resolve(repo, commitId));
            // 父提交必须显式 parse —— JGit 7.8 里 getParent() 返回的是轻量桩, getTree() 是 null
            RevCommit parent = commit.getParentCount() > 0
                    ? walk.parseCommit(commit.getParent(0).getId())
                    : null;
            return diffCommitTrees(repo, parent, commit);
        }
    }

    /** 两个 commit 之间的 diff */
    public List<FileDiff> diff(String path, String fromCommit, String toCommit) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit from = walk.parseCommit(resolve(repo, fromCommit));
            RevCommit to = walk.parseCommit(resolve(repo, toCommit));
            return diffCommitTrees(repo, from, to);
        }
        // 两个 commit 都是显式 parseCommit 出来的, getTree() 一定非 null
    }

    /** 工作区相对 HEAD 的未提交改动 */
    public List<FileDiff> workingTreeDiff(String path) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        RevCommit head = null;
        ObjectId headId = repo.resolve(Constants.HEAD);
        if (headId != null) {
            try (RevWalk walk = new RevWalk(repo)) {
                head = walk.parseCommit(headId);
            }
        }
        List<FileDiff> result = new ArrayList<>();
        try (DiffFormatter df = new DiffFormatter(new ByteArrayOutputStream())) {
            df.setRepository(repo);
            df.setDetectRenames(true);
            List<DiffEntry> entries;
            if (head != null) {
                try (ObjectReader reader = repo.newObjectReader()) {
                    CanonicalTreeParser oldTree = new CanonicalTreeParser();
                    oldTree.reset(reader, head.getTree().getId());
                    entries = df.scan(oldTree, new FileTreeIterator(repo));
                }
            } else {
                entries = df.scan(new EmptyTreeIterator(), new FileTreeIterator(repo));
            }
            for (DiffEntry entry : entries) {
                result.add(workingFileDiff(repo, entry));
            }
        }
        return result;
    }

    /**
     * 工作区单个文件的改动。
     *
     * <p>⚠️ 这里不能复用 {@code DiffFormatter.format(DiffEntry)}:
     * JGit 7.8 已经删掉了 {@code FileId} 类, 工作区文件在 DiffEntry 里只剩一个
     * 伪 SHA (AbbreviatedObjectId), DiffFormatter 分辨不出"这是工作区文件",
     * 会跑去 object database 里找那个根本不存在的 blob, 直接抛
     * {@code MissingObjectException: Missing blob xxxx}。
     *
     * <p>所以这里自己读两侧内容 (旧侧从 object database, 新侧从工作区文件),
     * 再用 {@link RawTextComparator} 算编辑列表, 自己拼 unified diff 文本。
     */
    private FileDiff workingFileDiff(Repository repo, DiffEntry entry) throws IOException {
        byte[] oldBytes = readOldSide(repo, entry);
        byte[] newBytes = readNewSide(repo, entry);

        if (isBinary(oldBytes) || isBinary(newBytes)) {
            return new FileDiff(entry.getNewPath(), entry.getOldPath(),
                    changeType(entry.getChangeType()), 0, 0, true, List.of());
        }

        RawText a = new RawText(oldBytes);
        RawText b = new RawText(newBytes);
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                .diff(RawTextComparator.DEFAULT, a, b);
        String patch = buildUnifiedDiff(entry, a, b, edits);
        return parsePatch(entry, patch);
    }

    /** 旧侧内容: HEAD 里的真 blob; 文件是新增的 (oldMode == MISSING) 时返回空 */
    private byte[] readOldSide(Repository repo, DiffEntry entry) throws IOException {
        if (entry.getOldMode() == FileMode.MISSING) {
            return new byte[0];
        }
        ObjectId id = entry.getOldId().toObjectId();
        return repo.open(id, Constants.OBJ_BLOB).getBytes();
    }

    /** 新侧内容: 直接读工作区文件; 文件被删除的 (newMode == MISSING) 时返回空 */
    private byte[] readNewSide(Repository repo, DiffEntry entry) throws IOException {
        if (entry.getNewMode() == FileMode.MISSING) {
            return new byte[0];
        }
        File file = new File(repo.getWorkTree(), entry.getNewPath());
        if (!file.isFile()) {
            return new byte[0];
        }
        return Files.readAllBytes(file.toPath());
    }

    /** 把 EditList 拼成 unified diff 文本 (git 风格, 3 行上下文) */
    private String buildUnifiedDiff(DiffEntry entry, RawText a, RawText b, EditList edits) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(entry.getOldPath()).append('\n');
        sb.append("+++ b/").append(entry.getNewPath()).append('\n');

        final int ctx = 3;
        int i = 0;
        while (i < edits.size()) {
            Edit first = edits.get(i);
            int hunkBeginA = Math.max(0, first.getBeginA() - ctx);
            int hunkEndA = Math.min(a.size(), first.getEndA() + ctx);
            int hunkBeginB = Math.max(0, first.getBeginB() - ctx);
            int hunkEndB = Math.min(b.size(), first.getEndB() + ctx);

            // 相邻的 edit 如果上下文能连上, 合并进同一个 hunk
            int j = i + 1;
            while (j < edits.size()) {
                Edit e = edits.get(j);
                int nextBeginA = Math.max(0, e.getBeginA() - ctx);
                if (nextBeginA > hunkEndA) {
                    break;
                }
                hunkEndA = Math.max(hunkEndA, Math.min(a.size(), e.getEndA() + ctx));
                hunkEndB = Math.max(hunkEndB, Math.min(b.size(), e.getEndB() + ctx));
                j++;
            }

            sb.append("@@ -").append(hunkBeginA + 1).append(',').append(hunkEndA - hunkBeginA)
                    .append(" +").append(hunkBeginB + 1).append(',').append(hunkEndB - hunkBeginB)
                    .append(" @@\n");

            int ai = hunkBeginA;
            int bi = hunkBeginB;
            for (int k = i; k < j; k++) {
                Edit e = edits.get(k);
                while (ai < e.getBeginA()) {
                    sb.append(' ').append(a.getString(ai)).append('\n');
                    ai++;
                    bi++;
                }
                while (ai < e.getEndA()) {
                    sb.append('-').append(a.getString(ai)).append('\n');
                    ai++;
                }
                while (bi < e.getEndB()) {
                    sb.append('+').append(b.getString(bi)).append('\n');
                    bi++;
                }
            }
            while (ai < hunkEndA) {
                sb.append(' ').append(a.getString(ai)).append('\n');
                ai++;
                bi++;
            }
            i = j;
        }
        return sb.toString();
    }

    /** 某个 commit 下某个文件的内容 */
    public String fileContent(String path, String commitId, String filePath) throws IOException {
        Git git = holder.open(path);
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(resolve(repo, commitId));
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(commit.getTree());
                tw.setRecursive(true);
                while (tw.next()) {
                    if (tw.getPathString().equals(filePath)) {
                        ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
                        return new String(loader.getBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 内部

    private List<FileDiff> diffCommitTrees(Repository repo, RevCommit from, RevCommit to)
            throws IOException {
        List<FileDiff> result = new ArrayList<>();
        try (DiffFormatter df = new DiffFormatter(new ByteArrayOutputStream())) {
            df.setRepository(repo);
            df.setDetectRenames(true);
            List<DiffEntry> entries;
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, to.getTree().getId());
                AbstractTreeIterator oldTree;
                if (from == null) {
                    // 根提交: 相对空树, 全部算新增
                    oldTree = new EmptyTreeIterator();
                } else {
                    CanonicalTreeParser p = new CanonicalTreeParser();
                    p.reset(reader, from.getTree().getId());
                    oldTree = p;
                }
                entries = df.scan(oldTree, newTree);
            }
            for (DiffEntry entry : entries) {
                result.add(toFileDiff(repo, entry));
            }
        }
        return result;
    }

    /** 单个文件的改动: 生成 unified diff 文本再解析成行 */
    private FileDiff toFileDiff(Repository repo, DiffEntry entry) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DiffFormatter df = new DiffFormatter(bos)) {
            df.setRepository(repo);
            df.format(entry);
        }
        String patch = bos.toString(StandardCharsets.UTF_8);
        return parsePatch(entry, patch);
    }

    private FileDiff parsePatch(DiffEntry entry, String patch) {
        List<DiffLine> lines = new ArrayList<>();
        int additions = 0;
        int deletions = 0;
        int oldNo = 0;
        int newNo = 0;
        boolean inHunk = false;

        for (String raw : patch.split("\n", -1)) {
            if (raw.startsWith("@@")) {
                inHunk = true;
                oldNo = parseHunkStart(raw, true);
                newNo = parseHunkStart(raw, false);
                lines.add(new DiffLine('@', oldNo, newNo, raw));
                continue;
            }
            if (!inHunk) {
                // --- / +++ / diff --git 文件头, 跳过
                continue;
            }
            if (raw.startsWith("\\")) {
                // "\ No newline at end of file"
                continue;
            }
            char origin = raw.isEmpty() ? ' ' : raw.charAt(0);
            String content = raw.isEmpty() ? "" : raw.substring(1);
            switch (origin) {
                case ' ' -> lines.add(new DiffLine(' ', oldNo++, newNo++, content));
                case '-' -> {
                    lines.add(new DiffLine('-', oldNo++, newNo, content));
                    deletions++;
                }
                case '+' -> {
                    lines.add(new DiffLine('+', oldNo, newNo++, content));
                    additions++;
                }
                default -> {
                    // 其它标记行忽略
                }
            }
        }
        // 没有 hunk = 二进制文件
        boolean binary = lines.isEmpty();
        return new FileDiff(
                entry.getNewPath(),
                entry.getOldPath(),
                changeType(entry.getChangeType()),
                additions,
                deletions,
                binary,
                lines);
    }

    private String changeType(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> "A";
            case DELETE -> "D";
            case RENAME -> "R";
            case COPY -> "C";
            case MODIFY -> "M";
        };
    }

    /** 解析 "@@ -12,7 +15,9 @@" 里的起始行号 */
    private int parseHunkStart(String header, boolean oldSide) {
        int minus = header.indexOf('-');
        int plus = header.indexOf('+');
        int end = header.indexOf("@@", plus);
        String seg;
        if (oldSide) {
            int to = plus > minus ? plus : header.length();
            seg = header.substring(minus + 1, to).trim();
        } else {
            int to = end > plus ? end : header.length();
            seg = header.substring(plus + 1, to).trim();
        }
        int comma = seg.indexOf(',');
        String num = (comma > 0 ? seg.substring(0, comma) : seg).trim();
        if (num.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isBinary(ObjectLoader loader) throws IOException {
        byte[] head = loader.getBytes(8000);
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /** 二进制判定 (直接看字节, 不走 ObjectLoader) */
    private boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private ObjectId resolve(Repository repo, String ref) throws IOException {
        ObjectId id = repo.resolve(ref);
        if (id == null) {
            id = ObjectId.fromString(ref);
        }
        return id;
    }
}
