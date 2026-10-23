package com.githistory.core.model;

import java.util.List;

/**
 * 仓库概览
 */
public record RepoInfo(
        String path,
        String name,
        String currentBranch,
        String headCommitId,
        boolean bare,
        List<String> remotes
) {
}
