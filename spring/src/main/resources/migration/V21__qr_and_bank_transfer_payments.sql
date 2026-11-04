-- QR payments and bank transfers (BE-045). The customer transfers the money outside the application (a banking or
-- payment app) and uploads the receipt; the money counts as received only when an admin approves the receipt after
-- checking the business account. Like CASH, a transfer has no payment provider, payment intent or card details.
-- Existing CARD/ONLINE/CASH payments and their transactions are unchanged; every existing transaction is given its
-- payment's method.

ALTER TABLE payments DROP CONSTRAINT ck_payments_method;
ALTER TABLE payments ADD CONSTRAINT ck_payments_method
    CHECK (method IN ('CARD', 'ONLINE', 'CASH', 'QR_TRANSFER', 'BANK_TRANSFER'));

-- V20's rule for CASH, extended to every method paid outside the payment provider.
ALTER TABLE payments DROP CONSTRAINT ck_payments_cash_no_provider;
ALTER TABLE payments ADD CONSTRAINT ck_payments_manual_no_provider CHECK (
    (method IN ('CASH', 'QR_TRANSFER', 'BANK_TRANSFER') AND provider IS NULL AND stripe_payment_id IS NULL
        AND payment_method_ref IS NULL AND balance_intent_id IS NULL AND card_last4 IS NULL)
    OR (method IN ('CARD', 'ONLINE') AND provider IS NOT NULL));

-- Money owed back to the customer (cash at the counter, or a transfer the admin makes); cleared only when an admin
-- records the refund.
ALTER TABLE payments DROP CONSTRAINT ck_payments_refund_due;
ALTER TABLE payments ADD CONSTRAINT ck_payments_refund_due CHECK (
    refund_due IS NULL OR (method IN ('CASH', 'QR_TRANSFER', 'BANK_TRANSFER') AND refund_due > 0));

-- How each recorded amount moved: the initial payment and the final balance may be paid in different ways at the
-- counter or by transfer (for example cash first, then the balance by bank transfer).
ALTER TABLE payment_transactions ADD COLUMN method VARCHAR(20);
UPDATE payment_transactions t SET method = p.method FROM payments p WHERE p.payment_id = t.payment_id;
ALTER TABLE payment_transactions ALTER COLUMN method SET NOT NULL;
ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_tx_method
    CHECK (method IN ('CARD', 'ONLINE', 'CASH', 'QR_TRANSFER', 'BANK_TRANSFER'));
-- A manual transfer: the customer's transfer reference, or the admin's reference and note for a refund they made.
ALTER TABLE payment_transactions ADD COLUMN reference VARCHAR(100);
ALTER TABLE payment_transactions ADD COLUMN note VARCHAR(500);

-- The business's payment-receiving details shown at checkout: one row, both methods off until an admin enables
-- them. Only public receiving details are kept (no passwords, PINs, OTPs, card numbers or banking logins). The QR
-- image is in the payment image store under a server-generated key.
CREATE TABLE payment_settings (
    payment_settings_id SMALLINT     PRIMARY KEY,
    qr_enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    qr_display_name     VARCHAR(100),
    qr_instructions     VARCHAR(300),
    qr_image_key        VARCHAR(64),
    qr_image_type       VARCHAR(20),
    qr_image_size       INTEGER,
    bank_enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
    bank_name           VARCHAR(100),
    bank_account_holder VARCHAR(100),
    bank_account_number VARCHAR(34),
    bank_branch         VARCHAR(100),
    bank_branch_code    VARCHAR(10),
    bank_instructions   VARCHAR(300),
    updated_at          TIMESTAMP,
    updated_by          UUID REFERENCES users (user_id),
    CONSTRAINT ck_payment_settings_single CHECK (payment_settings_id = 1),
    CONSTRAINT ck_payment_settings_qr_image CHECK (
        (qr_image_key IS NULL AND qr_image_type IS NULL AND qr_image_size IS NULL)
        OR (qr_image_key IS NOT NULL AND qr_image_type IN ('image/jpeg', 'image/png', 'image/webp')
            AND qr_image_size > 0 AND qr_image_size <= 5242880)),
    CONSTRAINT ck_payment_settings_qr_ready CHECK (
        NOT qr_enabled OR (qr_display_name IS NOT NULL AND qr_image_key IS NOT NULL)),
    CONSTRAINT ck_payment_settings_bank_ready CHECK (
        NOT bank_enabled OR (bank_name IS NOT NULL AND bank_account_holder IS NOT NULL
            AND bank_account_number IS NOT NULL AND bank_branch IS NOT NULL))
);
INSERT INTO payment_settings (payment_settings_id) VALUES (1);

-- A receipt a customer uploaded for a QR payment or bank transfer, for the initial payment or the final balance.
-- Receipts are never deleted: a rejected one stays with its reason, and a replacement is a new row. At most one per
-- payment waits for verification at a time. The image is in the payment image store under a server-generated key
-- (never the uploaded file name).
CREATE TABLE payment_receipts (
    payment_receipt_id UUID          PRIMARY KEY,
    payment_id         UUID          NOT NULL REFERENCES payments (payment_id),
    kind               VARCHAR(20)   NOT NULL,
    method             VARCHAR(20)   NOT NULL,
    amount             NUMERIC(10, 2) NOT NULL,
    transfer_reference VARCHAR(100),
    storage_key        VARCHAR(64)   NOT NULL,
    content_type       VARCHAR(20)   NOT NULL,
    size_bytes         INTEGER       NOT NULL,
    status             VARCHAR(30)   NOT NULL,
    submitted_at       TIMESTAMP     NOT NULL,
    reviewed_by        UUID          REFERENCES users (user_id),
    reviewed_at        TIMESTAMP,
    rejection_reason   VARCHAR(300),
    CONSTRAINT uq_payment_receipts_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_payment_receipts_kind CHECK (kind IN ('INITIAL', 'BALANCE')),
    CONSTRAINT ck_payment_receipts_method CHECK (method IN ('QR_TRANSFER', 'BANK_TRANSFER')),
    CONSTRAINT ck_payment_receipts_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_receipts_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_payment_receipts_size CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    CONSTRAINT ck_payment_receipts_status
        CHECK (status IN ('PENDING_VERIFICATION', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_payment_receipts_review CHECK (
        (status = 'PENDING_VERIFICATION' AND reviewed_at IS NULL AND reviewed_by IS NULL)
        OR (status = 'APPROVED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)
        OR (status = 'REJECTED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL
            AND rejection_reason IS NOT NULL)
        OR (status = 'EXPIRED' AND reviewed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_payment_receipts_one_pending ON payment_receipts (payment_id)
    WHERE status = 'PENDING_VERIFICATION';
CREATE INDEX idx_payment_receipts_status ON payment_receipts (status, submitted_at);
