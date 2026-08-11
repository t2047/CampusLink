package com.app.campusagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class DotenvConfig {

    @Bean
    public Dotenv dotenv() {
        return loadIntoSystemProperties();
    }

    /**
     * 加载 .env 并写入 System properties，必须在 SpringApplication 之前执行，
     * 否则 application.properties 中的 ${...} 占位符无法解析。
     *
     * dotenv-java 默认只从进程工作目录（CWD）查找 .env；为避免 IDE / 不同启动
     * 目录导致找不到仓库根 .env，这里从 CWD 向上回溯多级目录逐一探测。
     */
    public static Dotenv loadIntoSystemProperties() {
        Dotenv dotenv = findDotenv();
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        return dotenv;
    }

    private static Dotenv findDotenv() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        Path p = cwd;
        for (int i = 0; i < 5 && p != null; i++) {
            if (Files.isRegularFile(p.resolve(".env"))) {
                candidates.add(p);
            }
            p = p.getParent();
        }
        for (Path dir : candidates) {
            try {
                return Dotenv.configure().directory(dir.toString()).load();
            } catch (Exception ignored) {
                // 尝试下一个候选目录
            }
        }
        return Dotenv.configure().ignoreIfMissing().load();
    }
}
