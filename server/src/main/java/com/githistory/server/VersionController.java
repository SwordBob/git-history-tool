package com.githistory.server;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本信息
 *
 * <p>返回当前构建的时间戳 (取运行中的 jar 文件的修改时间), 界面上显示在左上角。
 *
 * <p>为什么需要这个: 前端是静态资源, 浏览器可能缓存旧的 js —— 改完代码
 * 用户界面还是老样子, 最难判断的就是"我到底跑的是不是新版本"。
 * 有了这个标识, 一眼就能对出来: 页面上显示的 build 时间比服务端 jar 的
 * 修改时间旧, 说明浏览器在用缓存。
 */
@RestController
public class VersionController {

    /** 服务端构建时间戳 */
    @GetMapping("/api/version")
    public Map<String, String> version() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("build", buildStamp());
        return out;
    }

    private String buildStamp() {
        try {
            File artifact = locateArtifact();
            if (artifact == null) {
                return "dev";
            }
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss");
            return fmt.format(new Date(artifact.lastModified()));
        } catch (Exception e) {
            return "dev";
        }
    }

    /**
     * 找到当前正在运行的那个 jar。
     *
     * <p>不能用 {@code getProtectionDomain().getCodeSource()} ——
     * Spring Boot fat jar 里类是从嵌套 jar 加载的, 那个 URL 是
     * {@code jar:nested:/...} 协议, 转成 File 会直接抛异常。
     *
     * <p>{@code sun.java.command} 在 {@code java -jar xxx.jar} 模式下就是主 jar 路径, 最直接。
     */
    private File locateArtifact() {
        // 1) java -jar 启动: sun.java.command 就是 jar 路径
        String cmd = System.getProperty("sun.java.command", "");
        if (cmd.endsWith(".jar")) {
            File jar = new File(cmd);
            if (jar.isFile()) {
                return jar;
            }
        }
        // 2) classpath 里的 jar (mvn spring-boot:run 之类)
        String cp = System.getProperty("java.class.path", "");
        File fallback = null;
        for (String part : cp.split(File.pathSeparator)) {
            File f = new File(part);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                fallback = f;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        // 3) codeSource 所在目录 (从 classes/ 跑的情况)
        try {
            return new File(getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception ignored) {
            return null;
        }
    }
}
