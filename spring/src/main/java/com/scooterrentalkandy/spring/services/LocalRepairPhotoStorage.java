package com.scooterrentalkandy.spring.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Repair photos on the local disk, in {@code app.repairs.photo-dir} (REPAIR_PHOTO_DIR). The directory is not served
 * by the web server: photos are only returned through the authorised repair photo endpoint. Keys are checked against
 * the generated format and resolved inside the directory, so a key can never name another path, and new files are
 * created exclusively, so nothing is overwritten.
 */
@Component
public class LocalRepairPhotoStorage extends LocalImageDirectory implements RepairPhotoStorage {

    public LocalRepairPhotoStorage(@Value("${app.repairs.photo-dir}") String directory) {
        super(directory, "repair photo", "Photo");
    }
}
