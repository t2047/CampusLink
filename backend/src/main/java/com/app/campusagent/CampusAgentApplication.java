package com.app.campusagent;

import com.app.campusagent.config.DotenvConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
public class CampusAgentApplication {

    public static void main(String[] args) {
 	DotenvConfig.loadIntoSystemProperties();
        SpringApplication.run(CampusAgentApplication.class, args);
    }
}