package com.githistory.core.model;

import java.util.List;

/**
 * 提交请求
 *
 * @param paths   要暂存并提交的文件路径; 为空且 stageAll=false 表示只提交已暂存内容
 * @param stageAll true = 先把所有改动 (含未跟踪) 全部加入暂存区
 */
public record CommitRequest(
        String message,
        List<String> paths,
        boolean stageAll
) {
}
