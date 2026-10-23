package com.githistory.core;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已打开仓库的缓存。
 *
 * <p>打开一次 JGit Repository 要扫 .git 目录、读 config、加载 packed-refs, 大仓上不便宜。
 * 前端每切一次分支就调一次接口, 不缓存的话每次都重开。所以这里按 canonical path 缓存。
 *
 * <p>注意: 缓存里的 Repository 只在进程存活期间有效, 外部用 git 命令改了 refs 之后
 * JGit 的 RefDatabase 可能有缓存, 严重不一致时调 {@link #invalidate(String)}。
 */
public class RepositoryHolder {

    private final Map<String, Git> cache = new ConcurrentHashMap<>();

    /**
     * 打开 (或复用) 一个仓库。
     *
     * @param path 仓库工作区目录, 可以是仓库内的子目录, 会向上找到 .git
     */
    public Git open(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("仓库路径不能为空");
        }
        File dir = new File(path);
        if (!dir.exists()) {
            throw new IOException("路径不存在: " + path);
        }
        String key = dir.getCanonicalPath();
        Git cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(dir);
            if (builder.getGitDir() == null) {
                throw new IOException("不是一个 Git 仓库 (向上也没找到 .git): " + path);
            }
            // 注意: JGit 7.8 里 setWorkTree + findGitDir() 之后 getGitDir() 仍然是 null,
            // 但 build() 会自己补全。所以这里用 findGitDir(dir) 定位, 交给 build() 收尾。
            Repository repo = builder.readEnvironment().build();
            Git git = new Git(repo);
            cache.put(key, git);
            return git;
        }
    }

    /** 让缓存失效, 下次 open 时重新读磁盘状态 */
    public void invalidate(String path) {
        try {
            String key = new File(path).getCanonicalPath();
            Git git = cache.remove(key);
            if (git != null) {
                git.getRepository().close();
            }
        } catch (IOException ignored) {
            // 路径都没法规范化, 那本来也不在缓存里
        }
    }

    /** 列出所有已缓存的仓库路径 */
    public java.util.List<String> openedPaths() {
        return java.util.List.copyOf(cache.keySet());
    }

    /** 当前分支短名; 游离 HEAD 时返回 null */
    public String currentBranch(Repository repo) throws IOException {
        String branch = repo.getBranch();
        return Constants.HEAD.equals(branch) ? null : branch;
    }
}
