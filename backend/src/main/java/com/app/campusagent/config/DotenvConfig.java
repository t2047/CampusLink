package com.app.campusagent.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DotenvConfig {

    @Bean
    public Dotenv dotenv() {
        return loadIntoSystemProperties();
    }

    public static Dotenv loadIntoSystemProperties() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        // Must run before SpringApplication so ${...} placeholders can resolve.
        dotenv.entries().forEach(e -> System.setProperty(e.getKey(), e.getValue()));
        return dotenv;
    }
}
