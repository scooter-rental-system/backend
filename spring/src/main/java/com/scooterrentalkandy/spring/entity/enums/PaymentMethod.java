package com.scooterrentalkandy.spring.entity.enums;

/**
 * CARD (and the earlier ONLINE) go through the payment provider. CASH is paid at the rental counter; QR_TRANSFER and
 * BANK_TRANSFER are transfers the customer makes outside the application and proves with a receipt. Those three are
 * manual: the money counts as received only when an admin records or approves it.
 */
public enum PaymentMethod {
    CARD, ONLINE, CASH, QR_TRANSFER, BANK_TRANSFER;

    /** Received outside the payment provider and recorded by an admin. */
    public boolean isManual() {
        return this == CASH || this == QR_TRANSFER || this == BANK_TRANSFER;
    }

    /** A QR payment or bank transfer, proven by an uploaded receipt. */
    public boolean isTransfer() {
        return this == QR_TRANSFER || this == BANK_TRANSFER;
    }
}
