package com.app.campusagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DotenvConfig {

    @Bean
    public Dotenv dotenv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        // Propagate to System properties so Spring can resolve ${MYSQL_URL} etc.
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        return dotenv;
    }
}
