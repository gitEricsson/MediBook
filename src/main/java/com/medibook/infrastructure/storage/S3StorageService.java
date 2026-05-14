package com.medibook.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3StorageService implements StorageService {
    private final S3Client s3Client;
    private final String bucketName;
    private final String region;

    public S3StorageService(S3Client s3Client,
                           @Value("${app.storage.s3.bucket}") String bucketName,
                           @Value("${app.storage.s3.region}") String region) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.region = region;
        log.info("S3StorageService initialized with bucket: {}, region: {}", bucketName, region);
    }

    @Override
    public String uploadFile(byte[] data, String filename, String contentType) throws StorageException {
        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(filename)
                    .contentType(contentType)
                    .build(),
                RequestBody.fromBytes(data)
            );

            String url = buildUrl(filename);
            log.info("File uploaded to S3: {}", filename);
            return url;
        } catch (Exception e) {
            log.error("Failed to upload file to S3: {}", filename, e);
            throw new StorageException("Failed to upload file to S3", e);
        }
    }

    @Override
    public void deleteFile(String fileUrl) throws StorageException {
        try {
            String key = extractKeyFromUrl(fileUrl);
            s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build());
            log.info("File deleted from S3: {}", key);
        } catch (Exception e) {
            log.error("Failed to delete file from S3: {}", fileUrl, e);
            throw new StorageException("Failed to delete file from S3", e);
        }
    }

    @Override
    public String getSignedUrl(String fileUrl) throws StorageException {
        // For public buckets, just return the URL as-is
        // For private buckets, implement pre-signed URL generation here
        return fileUrl;
    }

    private String buildUrl(String key) {
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, region, key);
    }

    private String extractKeyFromUrl(String url) {
        // Extract S3 key from URL
        // URL format: https://bucket.s3.region.amazonaws.com/key
        int lastSlashIndex = url.lastIndexOf('/');
        if (lastSlashIndex >= 0) {
            return url.substring(lastSlashIndex + 1);
        }
        return url;
    }
}
