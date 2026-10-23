package com.githistory.server;

import com.githistory.core.GitHistoryCore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Git 历史浏览器 · 本地服务
 */
@SpringBootApplication
public class GitHistoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitHistoryApplication.class, args);
    }

    @Bean
    public GitHistoryCore gitHistoryCore() {
        return new GitHistoryCore();
    }
}
