package com.githistory.core.model;

/**
 * 提交图的一行
 *
 * 前端直接拼 prefix + marker + suffix 渲染, 不需要懂 lane 逻辑。
 *
 * @param prefix 图左侧的竖线, 每个字符对应一个 lane
 * @param marker 提交标记: ● 普通提交, ○ merge commit
 * @param suffix 图右侧的分支出去线
 */
public record GraphRow(
        String prefix,
        String marker,
        String suffix,
        CommitNode commit
) {
    /** 渲染成一行纯文本, 便于 CLI / MCP 场景直接输出 */
    public String render() {
        return prefix + marker + suffix;
    }
}
