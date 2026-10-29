package com.scooterrentalkandy.spring.controllers;

import jakarta.validation.Valid;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.PaymentOptionsResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.PaymentSettingsResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.UpdatePaymentSettingsRequest;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.PaymentSettingsService;
import com.scooterrentalkandy.spring.services.PaymentSettingsService.ImageContent;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * QR payment and bank transfer details. /api/admin/** is ADMIN and SUPER_ADMIN only (SecurityConfig): only they see
 * and change the settings and the QR image. The checkout options are for signed-in users (the default rule) and show
 * only the enabled methods.
 */
@RestController
public class PaymentSettingsController {

    private final PaymentSettingsService settings;

    public PaymentSettingsController(PaymentSettingsService settings) {
        this.settings = settings;
    }

    /** The transfer methods offered at checkout now, with the details needed to pay. */
    @GetMapping("/api/payments/options")
    public PaymentOptionsResponse options() {
        return settings.options();
    }

    /** The QR code to scan at checkout, while QR payments are enabled. */
    @GetMapping("/api/payments/options/qr-image")
    public ResponseEntity<byte[]> qrImage() {
        return image(settings.qrImage());
    }

    @GetMapping("/api/admin/payment-settings")
    public PaymentSettingsResponse get() {
        return settings.get();
    }

    @PutMapping("/api/admin/payment-settings")
    public PaymentSettingsResponse update(@Valid @RequestBody UpdatePaymentSettingsRequest req,
                                          @CurrentUser AuthUser admin) {
        return settings.update(req, admin);
    }

    /** Uploads or replaces the QR image: one image part "image". */
    @PostMapping(path = "/api/admin/payment-settings/qr-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PaymentSettingsResponse uploadQrImage(@RequestPart(name = "image", required = false) MultipartFile image,
                                                 @CurrentUser AuthUser admin) {
        return settings.uploadQrImage(image, admin);
    }

    /** The uploaded QR image, enabled or not. */
    @GetMapping("/api/admin/payment-settings/qr-image")
    public ResponseEntity<byte[]> adminQrImage() {
        return image(settings.adminQrImage());
    }

    /** Served with its checked image type, never cached by shared caches and never sniffed as another type. */
    static ResponseEntity<byte[]> image(ImageContent content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(content.bytes());
    }
}
