package com.githistory.core.model;

import java.util.List;

/**
 * 提交节点
 *
 * @param branchLabels 指向该提交的分支名 (可多个, 如 main / origin/main / v1.0)
 */
public record CommitNode(
        String id,
        String shortId,
        String author,
        String email,
        String relativeTime,
        long timestamp,
        String subject,
        String message,
        List<String> parents,
        List<String> branchLabels
) {
}
