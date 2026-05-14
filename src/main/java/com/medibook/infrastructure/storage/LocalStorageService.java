package com.medibook.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {
    private final Path uploadDir;

    public LocalStorageService() throws StorageException {
        this.uploadDir = Paths.get("./uploads/avatars");
        try {
            Files.createDirectories(uploadDir);
            log.info("LocalStorageService initialized with upload directory: {}", uploadDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to create upload directory: {}", uploadDir, e);
            throw new StorageException("Failed to create upload directory", e);
        }
    }

    @Override
    public String uploadFile(byte[] data, String filename, String contentType) throws StorageException {
        try {
            Path filePath = uploadDir.resolve(filename);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            String url = "/uploads/avatars/" + filename;
            log.info("File uploaded locally: {}", filename);
            return url;
        } catch (Exception e) {
            log.error("Failed to upload file locally: {}", filename, e);
            throw new StorageException("Failed to upload file locally", e);
        }
    }

    @Override
    public void deleteFile(String fileUrl) throws StorageException {
        try {
            String relativePath = fileUrl.replace("/uploads/avatars/", "");
            Path filePath = uploadDir.resolve(relativePath);
            Files.deleteIfExists(filePath);
            log.info("File deleted locally: {}", relativePath);
        } catch (Exception e) {
            log.error("Failed to delete local file: {}", fileUrl, e);
            throw new StorageException("Failed to delete local file", e);
        }
    }

    @Override
    public String getSignedUrl(String fileUrl) throws StorageException {
        return fileUrl;
    }
}
