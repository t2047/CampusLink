package com.app.campusagent.lostfound.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfiguration {

    @Bean
    MinioClient minioClient(
            @Value("${app.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.storage.access-key:minioadmin}") String accessKey,
            @Value("${app.storage.secret-key:minioadmin}") String secretKey) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
