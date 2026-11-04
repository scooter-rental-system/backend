package com.scooterrentalkandy.spring.services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import com.scooterrentalkandy.spring.exception.ApiException;
import org.springframework.web.multipart.MultipartFile;

/**
 * Checks repair photo uploads: at most {@link #MAX_PHOTOS}, each a non-empty JPEG, PNG or WebP of at most
 * {@link #MAX_BYTES}. The type is decided by the file's own bytes (its signature and basic structure); the declared
 * content type and the file name's extension must agree with it. Anything else (SVG, HTML, scripts, executables,
 * renamed files) is refused. The file name is used for nothing else.
 */
public final class RepairPhotoValidator {

    public static final int MAX_PHOTOS = 3;
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    static final String TOO_MANY = "Attach at most " + MAX_PHOTOS + " photos.";
    static final String TOO_LARGE = "Each photo must be 5 MB or smaller.";
    static final String EMPTY = "A photo is empty. Choose the image again.";
    static final String WRONG_TYPE = "Photos must be JPEG, PNG or WebP images.";
    static final String UNREADABLE = "A photo could not be read as an image. Choose another file.";

    /** What the user is told when an image is refused; the checks are the same for every kind of image. */
    public record Messages(String empty, String tooLarge, String wrongType, String unreadable) {

        /** The messages for a single named image, such as "receipt" or "QR image". */
        public static Messages forOne(String noun) {
            return new Messages("The " + noun + " is empty. Choose the image again.",
                    "The " + noun + " must be 5 MB or smaller.",
                    "The " + noun + " must be a JPEG, PNG or WebP image.",
                    "The " + noun + " could not be read as an image. Choose another file.");
        }
    }

    private static final Messages PHOTO = new Messages(EMPTY, TOO_LARGE, WRONG_TYPE, UNREADABLE);

    /** An accepted image type: its media type, the extension used for storage and the extensions allowed on upload. */
    public enum ImageType {
        JPEG("image/jpeg", "jpg", Set.of("jpg", "jpeg")),
        PNG("image/png", "png", Set.of("png")),
        WEBP("image/webp", "webp", Set.of("webp"));

        public final String mediaType;
        final String extension;
        final Set<String> uploadExtensions;

        ImageType(String mediaType, String extension, Set<String> uploadExtensions) {
            this.mediaType = mediaType;
            this.extension = extension;
            this.uploadExtensions = uploadExtensions;
        }
    }

    /** A checked upload, ready to store. */
    public record CheckedPhoto(ImageType type, byte[] content) {
    }

    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private RepairPhotoValidator() {
    }

    /** A new random storage key for this type: a UUID and the type's extension, nothing from the upload. */
    public static String newKey(ImageType type) {
        return UUID.randomUUID() + "." + type.extension;
    }

    static boolean isKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** Checks every upload before anything is stored; one bad photo refuses the whole report. */
    public static List<CheckedPhoto> check(List<MultipartFile> files) {
        List<MultipartFile> present = files == null ? List.of() : files.stream().filter(f -> f != null).toList();
        if (present.size() > MAX_PHOTOS) {
            throw ApiException.badRequest(TOO_MANY);
        }
        return present.stream().map(RepairPhotoValidator::check).toList();
    }

    static CheckedPhoto check(MultipartFile file) {
        return check(file, PHOTO);
    }

    /**
     * One image (a payment receipt or the QR image) under the same rules as a repair photo: a non-empty JPEG, PNG or
     * WebP of at most {@link #MAX_BYTES}, decided by its own bytes.
     */
    public static CheckedPhoto check(MultipartFile file, Messages m) {
        if (file.isEmpty()) {
            throw ApiException.badRequest(m.empty());
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest(m.tooLarge());
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.badRequest(m.tooLarge());
        }
        ImageType type = detect(bytes);
        if (type == null || !declaredAs(file.getContentType(), type) || !extensionFits(file.getOriginalFilename(), type)) {
            throw ApiException.badRequest(m.wrongType());
        }
        if (!wellFormed(type, bytes)) {
            throw ApiException.badRequest(m.unreadable());
        }
        return new CheckedPhoto(type, bytes);
    }

    /** The type from the file signature, or null when it is none of the accepted ones. */
    static ImageType detect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return ImageType.JPEG;
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return ImageType.PNG;
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return ImageType.WEBP;
        }
        return null;
    }

    /** Basic structure beyond the signature, so a truncated or fake file is refused. */
    static boolean wellFormed(ImageType type, byte[] b) {
        return switch (type) {
            // An end-of-image marker after the header.
            case JPEG -> lastIndexOf(b, (byte) 0xFF, (byte) 0xD9) > 2;
            // The IHDR chunk first, with a non-zero width and height.
            case PNG -> b.length >= 33 && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R'
                    && int32(b, 16) > 0 && int32(b, 20) > 0;
            // The RIFF size covers the file, and the first chunk is an image chunk.
            case WEBP -> b.length >= 20 && littleInt32(b, 4) + 8L <= b.length && littleInt32(b, 4) >= 12
                    && b[12] == 'V' && b[13] == 'P' && b[14] == '8';
        };
    }

    private static boolean declaredAs(String contentType, ImageType type) {
        if (contentType == null) {
            return false;
        }
        String declared = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return declared.equals(type.mediaType) || (type == ImageType.JPEG && declared.equals("image/jpg"));
    }

    /** A file name without an extension is accepted; one with an extension must match the detected type. */
    private static boolean extensionFits(String name, ImageType type) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot < 0) {
            return true;
        }
        return type.uploadExtensions.contains(base.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static int lastIndexOf(byte[] b, byte first, byte second) {
        for (int i = b.length - 2; i >= 0; i--) {
            if (b[i] == first && b[i + 1] == second) {
                return i;
            }
        }
        return -1;
    }

    private static long int32(byte[] b, int at) {
        return ((long) (b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static long littleInt32(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((long) (b[at + 3] & 0xFF) << 24);
    }
}
