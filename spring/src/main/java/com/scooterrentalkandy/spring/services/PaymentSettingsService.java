package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.BankOption;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.PaymentOptionsResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.PaymentSettingsResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.QrOption;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.UpdatePaymentSettingsRequest;
import com.scooterrentalkandy.spring.entity.PaymentSettings;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.PaymentSettingsRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.RepairPhotoValidator.CheckedPhoto;
import com.scooterrentalkandy.spring.services.RepairPhotoValidator.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * The payment-receiving details for QR payments and bank transfers (BE-045). Admins (the /api/admin rule) see and
 * change everything; signed-in customers see only the methods that are enabled, with the details needed to pay.
 * Only public receiving details are kept: there is nowhere to enter a password, PIN, OTP, card number or banking
 * login. The QR image is checked like a repair photo (JPEG, PNG or WebP by its own bytes, at most 5 MB) and stored
 * under a new random key; the uploaded file name is never used.
 */
@Service
public class PaymentSettingsService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettingsService.class);
    static final Messages QR_IMAGE = Messages.forOne("QR image");
    /** Digits, optionally grouped with spaces or hyphens, as Sri Lankan account numbers are written. */
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9](?:[0-9 -]{2,32})[0-9]$");
    private static final Pattern BRANCH_CODE = Pattern.compile("^[0-9A-Za-z-]{1,10}$");

    /** An image to return through an authorised endpoint. */
    public record ImageContent(String contentType, byte[] bytes) {
    }

    private final PaymentSettingsRepository settings;
    private final UserRepository users;
    private final PaymentImageStorage images;
    private final Clock clock;
    private final int verificationHoldMinutes;

    public PaymentSettingsService(PaymentSettingsRepository settings, UserRepository users, PaymentImageStorage images,
                                  Clock clock,
                                  @Value("${app.payments.manual-verification-timeout-minutes}") int verificationHoldMinutes) {
        this.settings = settings;
        this.users = users;
        this.images = images;
        this.clock = clock;
        this.verificationHoldMinutes = verificationHoldMinutes;
    }

    // ---------------------------------------------------------------- customers

    /** The transfer methods offered at checkout now, with only the details needed to pay. */
    @Transactional(readOnly = true)
    public PaymentOptionsResponse options() {
        PaymentSettings s = current();
        QrOption qr = qrReady(s) ? new QrOption(s.getQrDisplayName(), s.getQrInstructions()) : null;
        BankOption bank = bankReady(s) ? new BankOption(s.getBankName(), s.getBankAccountHolder(),
                s.getBankAccountNumber(), s.getBankBranch(), s.getBankBranchCode(), s.getBankInstructions()) : null;
        return new PaymentOptionsResponse(qr, bank, BookingScheduler.PAYMENT_HOLD_MINUTES, verificationHoldMinutes);
    }

    /** The QR image, while QR payments are offered; otherwise it does not exist for customers. */
    @Transactional(readOnly = true)
    public ImageContent qrImage() {
        PaymentSettings s = current();
        if (!qrReady(s)) {
            throw ApiException.notFound("QR image");
        }
        return new ImageContent(s.getQrImageType(), images.read(s.getQrImageKey()));
    }

    /** Whether customers can choose this transfer method now. */
    @Transactional(readOnly = true)
    public boolean offers(PaymentMethod method) {
        PaymentSettings s = current();
        return switch (method) {
            case QR_TRANSFER -> qrReady(s);
            case BANK_TRANSFER -> bankReady(s);
            default -> false;
        };
    }

    // ---------------------------------------------------------------- admins

    @Transactional(readOnly = true)
    public PaymentSettingsResponse get() {
        return toResponse(current());
    }

    /** The image an admin uploaded, enabled or not. */
    @Transactional(readOnly = true)
    public ImageContent adminQrImage() {
        PaymentSettings s = current();
        if (s.getQrImageKey() == null) {
            throw ApiException.notFound("QR image");
        }
        return new ImageContent(s.getQrImageType(), images.read(s.getQrImageKey()));
    }

    @Transactional
    public PaymentSettingsResponse update(UpdatePaymentSettingsRequest req, AuthUser admin) {
        PaymentSettings s = settings.findForUpdate().orElseThrow(() -> new IllegalStateException("No payment settings"));
        String accountNumber = text(req.bankAccountNumber());
        if (accountNumber != null && !ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw ApiException.badRequest("The account number may contain only digits, spaces and hyphens");
        }
        String branchCode = text(req.bankBranchCode());
        if (branchCode != null && !BRANCH_CODE.matcher(branchCode).matches()) {
            throw ApiException.badRequest("The branch code may contain only letters, digits and hyphens");
        }
        s.setQrDisplayName(text(req.qrDisplayName()));
        s.setQrInstructions(text(req.qrInstructions()));
        s.setBankName(text(req.bankName()));
        s.setBankAccountHolder(text(req.bankAccountHolder()));
        s.setBankAccountNumber(accountNumber);
        s.setBankBranch(text(req.bankBranch()));
        s.setBankBranchCode(branchCode);
        s.setBankInstructions(text(req.bankInstructions()));
        if (req.qrEnabled() && s.getQrImageKey() == null) {
            throw ApiException.badRequest("Upload the QR image before enabling QR payments");
        }
        if (req.qrEnabled() && s.getQrDisplayName() == null) {
            throw ApiException.badRequest("Enter the name shown with the QR code before enabling QR payments");
        }
        if (req.bankEnabled() && (s.getBankName() == null || s.getBankAccountHolder() == null
                || s.getBankAccountNumber() == null || s.getBankBranch() == null)) {
            throw ApiException.badRequest("Enter the bank, account holder, account number and branch before "
                    + "enabling bank transfers");
        }
        s.setQrEnabled(req.qrEnabled());
        s.setBankEnabled(req.bankEnabled());
        touched(s, admin);
        log.info("Payment settings updated by admin {}: QR {}, bank transfer {}", admin.id(),
                s.isQrEnabled() ? "on" : "off", s.isBankEnabled() ? "on" : "off");
        return toResponse(s);
    }

    /**
     * Replaces the QR image. The new image is stored under a new random key; the old file is removed only once the
     * change is committed, and the new one is removed again if it is not.
     */
    @Transactional
    public PaymentSettingsResponse uploadQrImage(MultipartFile file, AuthUser admin) {
        if (file == null) {
            throw ApiException.badRequest("Choose the QR image to upload");
        }
        CheckedPhoto image = RepairPhotoValidator.check(file, QR_IMAGE);
        PaymentSettings s = settings.findForUpdate().orElseThrow(() -> new IllegalStateException("No payment settings"));
        String previous = s.getQrImageKey();
        String key = RepairPhotoValidator.newKey(image.type());
        images.save(key, image.content());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                remove(status == STATUS_COMMITTED ? previous : key);
            }
        });
        s.setQrImageKey(key);
        s.setQrImageType(image.type().mediaType);
        s.setQrImageSize(image.content().length);
        touched(s, admin);
        log.info("QR image replaced by admin {}", admin.id());
        return toResponse(s);
    }

    // ---------------------------------------------------------------- helpers

    private PaymentSettings current() {
        return settings.findCurrent().orElseThrow(() -> new IllegalStateException("No payment settings"));
    }

    private static boolean qrReady(PaymentSettings s) {
        return s.isQrEnabled() && s.getQrImageKey() != null && s.getQrDisplayName() != null;
    }

    private static boolean bankReady(PaymentSettings s) {
        return s.isBankEnabled() && s.getBankName() != null && s.getBankAccountHolder() != null
                && s.getBankAccountNumber() != null && s.getBankBranch() != null;
    }

    private void touched(PaymentSettings s, AuthUser admin) {
        s.setUpdatedAt(LocalDateTime.now(clock));
        s.setUpdatedBy(users.getReferenceById(admin.id()));
    }

    private void remove(String key) {
        if (key == null) {
            return;
        }
        try {
            images.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not remove a replaced or unsaved QR image ({})", e.getClass().getSimpleName());
        }
    }

    private static PaymentSettingsResponse toResponse(PaymentSettings s) {
        return new PaymentSettingsResponse(s.isQrEnabled(), s.getQrDisplayName(), s.getQrInstructions(),
                s.getQrImageKey() != null, s.isBankEnabled(), s.getBankName(), s.getBankAccountHolder(),
                s.getBankAccountNumber(), s.getBankBranch(), s.getBankBranchCode(), s.getBankInstructions(),
                s.getUpdatedAt(), s.getUpdatedBy() == null ? null : s.getUpdatedBy().getFullName());
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
