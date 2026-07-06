package com.shopee.monolith.modules.media.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local filesystem StorageService — active on profiles "local" and "test".
 * Stores files under ${app.media.storage.local-dir}.
 * getPublicUrl returns a localhost URL for local serving.
 */
@Slf4j
@Service
@Profile({"local", "test", "default"})
@ConditionalOnProperty(prefix = "app.media.storage", name = "type", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class LocalFileSystemStorageService implements StorageService {

    @Value("${app.media.storage.local-dir:${java.io.tmpdir}/shopee-media}")
    private String localDir;

    @Value("${app.media.storage.local-base-url:http://localhost:8080/api/media/files}")
    private String localBaseUrl;

    @Override
    public String store(byte[] bytes, String objectKey, String contentType) {
        try {
            Path dir = Paths.get(localDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(objectKey);
            try (OutputStream os = Files.newOutputStream(target)) {
                os.write(bytes);
            }
            log.debug("[LocalStorage] Stored {} bytes to {}", bytes.length, target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store file locally: " + objectKey, e);
        }
        return objectKey;
    }

    @Override
    public String getPublicUrl(String objectKey) {
        return localBaseUrl + "/" + objectKey;
    }

    @Override
    public byte[] load(String objectKey) {
        try {
            Path target = Paths.get(localDir).resolve(objectKey).normalize();
            Path root = Paths.get(localDir).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Invalid object key");
            }
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new AppException(ErrorCode.MEDIA_NOT_FOUND);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load file locally: " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Path target = Paths.get(localDir).resolve(objectKey);
            Files.deleteIfExists(target);
            log.debug("[LocalStorage] Deleted {}", target);
        } catch (IOException e) {
            log.warn("[LocalStorage] Failed to delete {}: {}", objectKey, e.getMessage());
        }
    }
}
