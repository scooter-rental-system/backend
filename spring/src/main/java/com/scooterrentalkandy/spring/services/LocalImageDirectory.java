package com.scooterrentalkandy.spring.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import com.scooterrentalkandy.spring.exception.ApiException;

/**
 * Checked images on the local disk, in one directory that the web server does not serve: the files are only
 * returned through authorised endpoints. Keys are checked against the generated format
 * ({@link RepairPhotoValidator#newKey}) and resolved inside the directory, so a key can never name another path,
 * and new files are created exclusively, so nothing is overwritten.
 */
abstract class LocalImageDirectory {

    private final Path root;
    private final String what;
    private final String notFound;

    /** {@code what}: the kind of image, for error messages; {@code notFound}: what a 404 calls a missing one. */
    LocalImageDirectory(String directory, String what, String notFound) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.what = what;
        this.notFound = notFound;
    }

    public void save(String key, byte[] content) {
        try {
            Files.createDirectories(root);
            Files.write(resolve(key), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("The " + what + " key is already in use", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the " + what, e);
        }
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (NoSuchFileException e) {
            throw ApiException.notFound(notFound);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the " + what, e);
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete the " + what, e);
        }
    }

    private Path resolve(String key) {
        if (!RepairPhotoValidator.isKey(key)) {
            throw new IllegalArgumentException("Not an image key");
        }
        Path p = root.resolve(key).normalize();
        if (!p.getParent().equals(root)) {
            throw new IllegalArgumentException("Not an image key");
        }
        return p;
    }
}
