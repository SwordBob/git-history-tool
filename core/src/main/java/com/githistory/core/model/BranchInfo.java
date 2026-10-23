package com.githistory.core.model;

/**
 * 分支信息
 *
 * @param name       短名, 如 main / feature/login
 * @param fullName   全名, 如 refs/heads/main
 * @param remote     是否为远程分支
 * @param current    是否为当前 HEAD 所指分支
 * @param ahead      相对 upstream 领先多少个 commit
 * @param behind     相对 upstream 落后多少个 commit
 */
public record BranchInfo(
        String name,
        String fullName,
        String commitId,
        String shortCommitId,
        String author,
        String relativeTime,
        long timestamp,
        boolean remote,
        boolean current,
        long ahead,
        long behind
) {
}
