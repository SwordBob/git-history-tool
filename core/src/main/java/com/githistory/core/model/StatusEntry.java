package com.githistory.core.model;

/**
 * 工作区文件状态
 *
 * @param status ADDED / MODIFIED / DELETED / UNTRACKED / CONFLICTED
 * @param staged 是否已在暂存区
 */
public record StatusEntry(
        String path,
        String status,
        boolean staged
) {
}
