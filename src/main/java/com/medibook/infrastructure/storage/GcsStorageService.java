package com.medibook.infrastructure.storage;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "gcs")
public class GcsStorageService implements StorageService {

    private final Storage storage;
    private final String bucketName;

    public GcsStorageService(
            @Value("${app.storage.gcs.bucket}") String bucketName,
            @Value("${app.storage.gcs.project-id}") String projectId,
            @Value("${app.storage.gcs.credentials-path:}") String credentialsPath) throws IOException {

        this.bucketName = bucketName;

        StorageOptions.Builder builder = StorageOptions.newBuilder().setProjectId(projectId);
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            builder.setCredentials(
                    GoogleCredentials.fromStream(new FileInputStream(credentialsPath))
                            .createScoped("https://www.googleapis.com/auth/cloud-platform"));
        }
        // If no credentials path, fall back to Application Default Credentials (ADC):
        // GOOGLE_APPLICATION_CREDENTIALS env var, gcloud CLI, or Workload Identity.
        this.storage = builder.build().getService();
        log.info("GcsStorageService initialized — bucket: {}, project: {}", bucketName, projectId);
    }

    @Override
    public String uploadFile(byte[] data, String filename, String contentType) throws StorageException {
        try {
            BlobId blobId = BlobId.of(bucketName, filename);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(contentType)
                    .build();
            storage.create(blobInfo, data);
            String url = buildPublicUrl(filename);
            log.info("File uploaded to GCS: {}", filename);
            return url;
        } catch (Exception e) {
            log.error("Failed to upload file to GCS: {}", filename, e);
            throw new StorageException("Failed to upload file to GCS", e);
        }
    }

    @Override
    public void deleteFile(String fileUrl) throws StorageException {
        try {
            String key = extractKeyFromUrl(fileUrl);
            BlobId blobId = BlobId.of(bucketName, key);
            boolean deleted = storage.delete(blobId);
            if (deleted) {
                log.info("File deleted from GCS: {}", key);
            } else {
                log.warn("GCS delete: object not found — {}", key);
            }
        } catch (Exception e) {
            log.error("Failed to delete file from GCS: {}", fileUrl, e);
            throw new StorageException("Failed to delete file from GCS", e);
        }
    }

    @Override
    public String getSignedUrl(String fileUrl) throws StorageException {
        try {
            String key = extractKeyFromUrl(fileUrl);
            BlobId blobId = BlobId.of(bucketName, key);
            com.google.cloud.storage.Blob blob = storage.get(blobId);
            if (blob == null) {
                return fileUrl;
            }
            return blob.signUrl(1, TimeUnit.HOURS,
                    Storage.SignUrlOption.withV4Signature()).toString();
        } catch (Exception e) {
            log.error("Failed to generate signed URL for GCS object: {}", fileUrl, e);
            throw new StorageException("Failed to generate signed URL", e);
        }
    }

    private String buildPublicUrl(String key) {
        return String.format("https://storage.googleapis.com/%s/%s", bucketName, key);
    }

    private String extractKeyFromUrl(String url) {
        // URL format: https://storage.googleapis.com/bucket/key
        String prefix = "https://storage.googleapis.com/" + bucketName + "/";
        if (url.startsWith(prefix)) {
            return URLDecoder.decode(url.substring(prefix.length()), StandardCharsets.UTF_8);
        }
        int lastSlash = url.lastIndexOf('/');
        return lastSlash >= 0 ? url.substring(lastSlash + 1) : url;
    }
}
