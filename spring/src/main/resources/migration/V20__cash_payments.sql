-- Cash at the rental counter (BE-044): the customer chooses cash and an admin records the money when it is handed
-- over (and any cash handed back). No payment provider is involved, so a cash payment has no provider, payment
-- intent, saved payment method or card digits. Existing payments are all CARD/ONLINE with a provider and are
-- unchanged.

ALTER TABLE payments ALTER COLUMN provider DROP NOT NULL;

ALTER TABLE payments DROP CONSTRAINT IF EXISTS ck_payments_method;
ALTER TABLE payments ADD CONSTRAINT ck_payments_method CHECK (method IN ('CARD', 'ONLINE', 'CASH'));

ALTER TABLE payments ADD CONSTRAINT ck_payments_cash_no_provider CHECK (
    (method = 'CASH' AND provider IS NULL AND stripe_payment_id IS NULL AND payment_method_ref IS NULL
        AND balance_intent_id IS NULL AND card_last4 IS NULL)
    OR (method <> 'CASH' AND provider IS NOT NULL));

-- Cash to hand back at the counter (the final cost is below the cash received, or a paid booking was cancelled);
-- NULL when nothing is to be handed back. Only an admin's confirmation clears it.
ALTER TABLE payments ADD COLUMN refund_due NUMERIC(10, 2);
ALTER TABLE payments ADD CONSTRAINT ck_payments_refund_due CHECK (
    refund_due IS NULL OR (method = 'CASH' AND refund_due > 0));

-- The admin who recorded a cash receipt or cash refund; NULL for payment-provider transactions.
ALTER TABLE payment_transactions ADD COLUMN recorded_by UUID REFERENCES users (user_id);
