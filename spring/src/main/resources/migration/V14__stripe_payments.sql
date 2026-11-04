-- BE-033: real Stripe payments.

-- Stripe Customer of each user. The remaining balance is charged off-session to the payment method saved on the
-- customer during the initial payment (SDS 4.3 chargeRemainingBalance); Stripe needs a Customer for that.
ALTER TABLE users ADD COLUMN stripe_customer_id VARCHAR(255);
ALTER TABLE users ADD CONSTRAINT ux_users_stripe_customer_id UNIQUE (stripe_customer_id);

-- Every provider webhook event that was processed, so a repeated delivery never repeats a business action.
CREATE TABLE payment_provider_events (
    payment_provider_event_id UUID         PRIMARY KEY,
    provider                  VARCHAR(30)  NOT NULL,
    event_id                  VARCHAR(255) NOT NULL,
    event_type                VARCHAR(100) NOT NULL,
    processed_at              TIMESTAMP    NOT NULL,
    CONSTRAINT ux_payment_provider_events UNIQUE (provider, event_id)
);

-- The payment intent a refund was made against (a booking's payment can span several intents).
ALTER TABLE payment_transactions ADD COLUMN intent_ref VARCHAR(255);
