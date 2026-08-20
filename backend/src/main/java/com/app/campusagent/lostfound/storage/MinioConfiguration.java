/**
 * MinIO 对象存储配置类。
 * <p>负责为失物招领（lost-found）模块装配唯一的 {@link MinioClient} Bean，
 * 供 {@link MinioObjectStorageService} 注入使用，完成失物招领图片的上传、
 * 下载、临时访问 URL 生成与删除等对象存储操作。
 * <p>连接参数（endpoint / access-key / secret-key）均通过 Spring {@code @Value}
 * 从配置文件读取，并带默认值兜底，方便本地开发无需额外配置即可直连默认的 MinIO 服务。
 */
package com.app.campusagent.lostfound.storage;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 声明为 Spring 配置类：方法返回的 {@link MinioClient} 会注册为容器 Bean 供全局注入。 */
@Configuration
public class MinioConfiguration {

    /**
     * 生产 MinIO 客户端 Bean。
     *
     * @param endpoint  MinIO 服务地址；配置项 {@code app.storage.endpoint}，缺省 http://localhost:9000
     * @param accessKey 访问密钥 AccessKey；配置项 {@code app.storage.access-key}，缺省 minioadmin
     * @param secretKey 访问密钥 SecretKey；配置项 {@code app.storage.secret-key}，缺省 minioadmin
     * @return 已绑定 endpoint 与凭据的 MinioClient，供后续桶/对象的读写操作使用
     */
    @Bean
    MinioClient minioClient(
            @Value("${app.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.storage.access-key:minioadmin}") String accessKey,
            @Value("${app.storage.secret-key:minioadmin}") String secretKey) {
        // 使用 MinIO 官方建造者模式（Builder）装配客户端：
        return MinioClient.builder()
                .endpoint(endpoint)                // 指定 MinIO / S3 兼容服务的访问地址
                .credentials(accessKey, secretKey) // 指定访问凭据，用于请求签名与鉴权
                .build();                          // 构建不可变的 MinioClient 实例
    }
}
