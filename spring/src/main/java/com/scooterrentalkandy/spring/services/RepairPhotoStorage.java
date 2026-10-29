package com.scooterrentalkandy.spring.services;

/**
 * Where repair photo bytes are kept, by server-generated key ({@link RepairPhotoValidator#newKey}). The repair logic
 * depends only on this interface; {@link LocalRepairPhotoStorage} keeps them on the local disk, and a deployment can
 * provide another implementation (for example object storage) instead.
 */
public interface RepairPhotoStorage {

    /** Stores new content; never overwrites an existing key. */
    void save(String key, byte[] content);

    /** The content, or an ApiException (404) when the key is unknown. */
    byte[] read(String key);

    /** Removes the content if present (used to undo a failed report). */
    void delete(String key);
}
