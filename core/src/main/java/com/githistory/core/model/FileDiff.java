package com.githistory.core.model;

import java.util.List;

/**
 * 单个文件的改动
 *
 * @param changeType A=新增 M=修改 D=删除 R=重命名
 * @param binary     是否二进制文件 (没有行级 hunk)
 */
public record FileDiff(
        String path,
        String oldPath,
        String changeType,
        int additions,
        int deletions,
        boolean binary,
        List<DiffLine> lines
) {
}
