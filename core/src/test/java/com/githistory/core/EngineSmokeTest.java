package com.githistory.core;

import com.githistory.core.model.BranchInfo;
import com.githistory.core.model.CommitNode;
import com.githistory.core.model.DiffLine;
import com.githistory.core.model.FileDiff;
import com.githistory.core.model.GraphRow;
import com.githistory.core.model.StatusEntry;
import com.githistory.core.model.CommitRequest;

import java.util.List;

/**
 * 引擎冒烟测试 (不是 JUnit, 直接 main 跑, 方便本地快速验证)。
 *
 * <p>用法: java -cp ... com.githistory.core.EngineSmokeTest &lt;仓库路径&gt;
 */
public class EngineSmokeTest {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : ".";
        GitHistoryCore core = new GitHistoryCore();

        System.out.println("========== 1. 仓库概览 ==========");
        var info = core.info(path);
        System.out.printf("名称=%s%n当前分支=%s%nHEAD=%s%nbare=%s%nremotes=%s%n",
                info.name(), info.currentBranch(), info.headCommitId(), info.bare(), info.remotes());

        System.out.println("\n========== 2. 分支列表 ==========");
        List<BranchInfo> branches = core.branches(path);
        for (BranchInfo b : branches) {
            System.out.printf("  %-28s %s  %-10s  %-18s  ahead=%d behind=%d%s%n",
                    b.name(), b.shortCommitId(), b.remote() ? "[remote]" : "[local]",
                    b.relativeTime(), b.ahead(), b.behind(),
                    b.current() ? "  <== 当前" : "");
        }

        System.out.println("\n========== 3. 提交历史 + 提交图 ==========");
        List<GraphRow> history = core.history(path, "HEAD", 20);
        for (GraphRow row : history) {
            CommitNode c = row.commit();
            System.out.printf("  %s %s  %-20s %s%n",
                    row.render(), c.shortId(), c.relativeTime(),
                    c.subject() + (c.branchLabels().isEmpty() ? "" : "  " + c.branchLabels()));
        }

        if (history.isEmpty()) {
            System.out.println("  (没有提交, 后面几步跳过)");
            return;
        }

        System.out.println("\n========== 4. 某个 commit 改了哪些文件 ==========");
        String firstCommit = history.get(0).commit().id();
        List<FileDiff> changed = core.filesChangedIn(path, firstCommit);
        for (FileDiff d : changed) {
            System.out.printf("  %s %-50s +%d -%d%s%n",
                    d.changeType(), d.path(), d.additions(), d.deletions(),
                    d.binary() ? "  [二进制]" : "");
        }

        System.out.println("\n========== 5. 提交详情 diff 内容 (前 25 行) ==========");
        for (FileDiff d : changed) {
            System.out.printf("  --- %s (%s)%n", d.path(), d.changeType());
            int n = 0;
            for (DiffLine l : d.lines()) {
                if (n++ >= 25) {
                    System.out.println("      ...");
                    break;
                }
                System.out.printf("      %c%-4d %-4d %s%n", l.origin(), l.oldNo(), l.newNo(), l.content());
            }
        }

        System.out.println("\n========== 6. 两个 commit 之间的 diff ==========");
        if (history.size() >= 2) {
            String a = history.get(1).commit().id();
            String b = history.get(0).commit().id();
            List<FileDiff> d2 = core.diff(path, a, b);
            System.out.printf("  %s -> %s  改动 %d 个文件%n", a.substring(0, 8), b.substring(0, 8), d2.size());
            for (FileDiff d : d2) {
                System.out.printf("    %s %-50s +%d -%d%n", d.changeType(), d.path(), d.additions(), d.deletions());
            }
        }

        System.out.println("\n========== 7. 某个 commit 下的文件列表 (前 10 个) ==========");
        var files = core.filesAt(path, firstCommit);
        System.out.printf("  共 %d 个文件%n", files.size());
        files.stream().limit(10).forEach(f ->
                System.out.printf("    %-55s %8d bytes%s%n", f.path(), f.size(), f.binary() ? "  [二进制]" : ""));

        System.out.println("\n========== 8. 工作区状态 ==========");
        List<StatusEntry> status = core.status(path);
        if (status.isEmpty()) {
            System.out.println("  (干净)");
        } else {
            for (StatusEntry s : status) {
                System.out.printf("  %-10s %-8s %s%n", s.status(), s.staged() ? "已暂存" : "未暂存", s.path());
            }
        }

        System.out.println("\n========== 9. 文件内容读取 (第一个 java 文件) ==========");
        var javaFile = files.stream()
                .filter(f -> f.path().endsWith(".java"))
                .findFirst();
        if (javaFile.isPresent()) {
            String content = core.fileContent(path, firstCommit, javaFile.get().path());
            String[] lines = content.split("\n");
            System.out.printf("  %s 共 %d 行, 前 3 行:%n", javaFile.get().path(), lines.length);
            for (int i = 0; i < Math.min(3, lines.length); i++) {
                System.out.println("    | " + lines[i]);
            }
        }

        System.out.println("\n========== 10. diff 文本渲染 (给 LLM 看的格式) ==========");
        String rendered = core.renderDiff(changed);
        System.out.println("  " + rendered.lines().limit(8).reduce("", (a, b) -> a + "\n  " + b));

        System.out.println("\n✅ 引擎全部步骤跑通");
    }
}
