package com.medibook.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for serving uploaded files from local storage during development.
 * For production, avatars are served directly from S3/CloudFront.
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.storage.local.path:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("Registering resource handler for local uploads directory: {}", uploadDir);
        registry.addResourceHandler("/uploads/**")
            .addResourceLocations("file:" + uploadDir + "/")
            .setCachePeriod(3600);
    }
}
