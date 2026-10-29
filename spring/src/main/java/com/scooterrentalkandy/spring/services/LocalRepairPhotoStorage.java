package com.scooterrentalkandy.spring.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.scooterrentalkandy.spring.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Repair photos on the local disk, in {@code app.repairs.photo-dir} (REPAIR_PHOTO_DIR). The directory is not served
 * by the web server: photos are only returned through the authorised repair photo endpoint. Keys are checked against
 * the generated format and resolved inside the directory, so a key can never name another path, and new files are
 * created exclusively, so nothing is overwritten.
 */
@Component
public class LocalRepairPhotoStorage implements RepairPhotoStorage {

    private final Path root;

    public LocalRepairPhotoStorage(@Value("${app.repairs.photo-dir}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void save(String key, byte[] content) {
        try {
            Files.createDirectories(root);
            Files.write(resolve(key), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("Repair photo key already in use", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the repair photo", e);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (NoSuchFileException e) {
            throw ApiException.notFound("Photo");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the repair photo", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the repair photo", e);
        }
    }

    private Path resolve(String key) {
        if (!RepairPhotoValidator.isKey(key)) {
            throw new IllegalArgumentException("Not a repair photo key");
        }
        Path p = root.resolve(key).normalize();
        if (!p.getParent().equals(root)) {
            throw new IllegalArgumentException("Not a repair photo key");
        }
        return p;
    }
}
