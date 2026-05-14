package com.medibook.infrastructure.storage;

public interface StorageService {
    /**
     * Upload a file and return the URL
     */
    String uploadFile(byte[] data, String filename, String contentType) throws StorageException;

    /**
     * Delete a file by URL
     */
    void deleteFile(String fileUrl) throws StorageException;

    /**
     * Get a pre-signed URL for private buckets (expires in 1 hour)
     */
    String getSignedUrl(String fileUrl) throws StorageException;
}
