package com.githistory.core.model;

/**
 * diff 的一行
 *
 * @param origin ' ' 上下文, '+' 新增, '-' 删除, '@' hunk 头
 */
public record DiffLine(
        char origin,
        int oldNo,
        int newNo,
        String content
) {
}
