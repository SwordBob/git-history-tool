package com.githistory.core.model;

/**
 * 提交结果
 */
public record CommitResult(
        String commitId,
        String shortId,
        String message,
        int filesChanged,
        String branch
) {
}
