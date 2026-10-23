package com.githistory.core.model;

/**
 * 某个 commit 下的文件
 */
public record FileEntry(
        String path,
        long size,
        boolean binary,
        String mode
) {
}
