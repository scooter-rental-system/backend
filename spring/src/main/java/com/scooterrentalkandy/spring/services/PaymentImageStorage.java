package com.scooterrentalkandy.spring.services;

/**
 * Where the QR image and transfer receipts are kept, by server-generated key ({@link RepairPhotoValidator#newKey}).
 * The payment logic depends only on this interface; {@link LocalPaymentImageStorage} keeps them on the local disk, and
 * a deployment can provide another implementation (for example object storage) instead.
 */
public interface PaymentImageStorage {

    /** Stores new content; never overwrites an existing key. */
    void save(String key, byte[] content);

    /** The content, or an ApiException (404) when the key is unknown. */
    byte[] read(String key);

    /** Removes the content if present. */
    void delete(String key);
}
