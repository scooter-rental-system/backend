package com.scooterrentalkandy.spring.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The QR image and transfer receipts on the local disk, in {@code app.payments.image-dir} (PAYMENT_IMAGE_DIR), apart
 * from repair photos. Not served by the web server: only returned through the authorised QR and receipt endpoints.
 */
@Component
public class LocalPaymentImageStorage extends LocalImageDirectory implements PaymentImageStorage {

    public LocalPaymentImageStorage(@Value("${app.payments.image-dir}") String directory) {
        super(directory, "payment image", "Image");
    }
}
