package com.medibook.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class AwsS3Config {

    @Bean
    public S3Client s3Client() {
        log.info("Initializing S3Client for AWS S3 operations");
        return S3Client.builder().build();
    }
}
