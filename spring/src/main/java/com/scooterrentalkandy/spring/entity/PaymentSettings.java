package com.scooterrentalkandy.spring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * The business's payment-receiving details for QR payments and bank transfers (V21): a single row, both methods off
 * until an admin enables them. Only public receiving details: never a password, PIN, OTP, card number or banking
 * login. The QR image is in the payment image store under {@code qrImageKey}, a server-generated name.
 */
@Entity
@Table(name = "payment_settings")
@Getter
@Setter
public class PaymentSettings {

    public static final short ID = 1;

    @Id
    @Column(name = "payment_settings_id", nullable = false, updatable = false)
    private short id;

    @Column(name = "qr_enabled", nullable = false)
    private boolean qrEnabled;

    @Column(name = "qr_display_name", length = 100)
    private String qrDisplayName;

    @Column(name = "qr_instructions", length = 300)
    private String qrInstructions;

    @Column(name = "qr_image_key", length = 64)
    private String qrImageKey;

    @Column(name = "qr_image_type", length = 20)
    private String qrImageType;

    @Column(name = "qr_image_size")
    private Integer qrImageSize;

    @Column(name = "bank_enabled", nullable = false)
    private boolean bankEnabled;

    @Column(name = "bank_name", length = 100)
    private String bankName;

    @Column(name = "bank_account_holder", length = 100)
    private String bankAccountHolder;

    @Column(name = "bank_account_number", length = 34)
    private String bankAccountNumber;

    @Column(name = "bank_branch", length = 100)
    private String bankBranch;

    @Column(name = "bank_branch_code", length = 10)
    private String bankBranchCode;

    @Column(name = "bank_instructions", length = 300)
    private String bankInstructions;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;
}
