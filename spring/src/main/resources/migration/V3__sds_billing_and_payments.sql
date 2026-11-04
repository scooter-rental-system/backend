-- SDS billing, rental lifecycle and payment model (decisions E1, E2, E4, E6).
-- Open-ended rentals billed (hours x hourlyRate) + (km x perKmRate) + gear; one payment per booking; no deposit.
-- Every value this migration drops or merges is first copied into a legacy_* table, so nothing is lost.

-- ---------------------------------------------------------------- archives of the old model

CREATE TABLE legacy_payments AS SELECT * FROM payments;

CREATE TABLE legacy_booking_terms AS
SELECT id AS booking_id, start_date, end_date, rental_days, scooter_cost, gear_cost, deposit_amount, total_amount,
       start_odometer, end_odometer, damage_charge, checked_out_at, returned_at
  FROM bookings;

CREATE TABLE legacy_booking_gear_rates AS SELECT id AS booking_gear_id, booking_id, daily_rate, line_total FROM booking_gear;

CREATE TABLE legacy_scooter_rates AS SELECT id AS scooter_id, daily_rate, deposit_amount FROM scooters;

-- ---------------------------------------------------------------- scooters: hourly and per-km rates (SDS 2.2)

ALTER TABLE scooters ADD COLUMN hourly_rate NUMERIC(10, 2);
ALTER TABLE scooters ADD COLUMN per_km_rate NUMERIC(10, 2);
-- Same price for a full day as before; per-km pricing starts at zero until an admin sets it.
UPDATE scooters SET hourly_rate = ROUND(daily_rate / 24, 2), per_km_rate = 0;
ALTER TABLE scooters ALTER COLUMN hourly_rate SET NOT NULL;
ALTER TABLE scooters ALTER COLUMN per_km_rate SET NOT NULL;
ALTER TABLE scooters ADD CONSTRAINT ck_scooters_hourly_rate CHECK (hourly_rate > 0);
ALTER TABLE scooters ADD CONSTRAINT ck_scooters_per_km_rate CHECK (per_km_rate >= 0);
ALTER TABLE scooters DROP COLUMN daily_rate;
ALTER TABLE scooters DROP COLUMN deposit_amount;

-- ---------------------------------------------------------------- bookings: open-ended times (SDS 2.2)

ALTER TABLE bookings ADD COLUMN start_time TIMESTAMP;
ALTER TABLE bookings ADD COLUMN end_time TIMESTAMP;
ALTER TABLE bookings ADD COLUMN distance_km NUMERIC(10, 2);
ALTER TABLE bookings ADD COLUMN total_cost NUMERIC(10, 2);
-- The rental started at handover; bookings never handed over start at the beginning of their first day.
UPDATE bookings SET start_time = COALESCE(checked_out_at, CAST(start_date AS TIMESTAMP));
UPDATE bookings SET end_time = returned_at WHERE status = 'COMPLETED';
UPDATE bookings SET distance_km = end_odometer - start_odometer
 WHERE status = 'COMPLETED' AND start_odometer IS NOT NULL AND end_odometer IS NOT NULL;

-- ---------------------------------------------------------------- GPS logs belong to the booking (SDS 2.2, 2.3)

ALTER TABLE gps_pings ADD COLUMN booking_id BIGINT;
UPDATE gps_pings SET booking_id = (
    SELECT MIN(b.id) FROM bookings b
     WHERE b.scooter_id = gps_pings.scooter_id
       AND b.status IN ('ACTIVE', 'COMPLETED')
       AND b.checked_out_at IS NOT NULL
       AND gps_pings.recorded_at >= b.checked_out_at
       AND (b.returned_at IS NULL OR gps_pings.recorded_at <= b.returned_at));
ALTER TABLE gps_pings ADD CONSTRAINT fk_gps_pings_booking FOREIGN KEY (booking_id) REFERENCES bookings (id);
CREATE INDEX idx_gps_pings_booking_time ON gps_pings (booking_id, recorded_at);

-- ---------------------------------------------------------------- payments: one row per booking (SDS 2.2, 2.3)

ALTER TABLE payments ADD COLUMN paid_at TIMESTAMP;
ALTER TABLE payments ADD COLUMN payment_method_ref VARCHAR(255);
ALTER TABLE payments ADD COLUMN balance_due NUMERIC(10, 2);
ALTER TABLE payments ADD COLUMN balance_intent_id VARCHAR(255);

-- Per booking: the first successful charge survives and carries the net amount collected.
CREATE TABLE payment_rollup AS
SELECT p.booking_id,
       COALESCE(MIN(CASE WHEN p.type IN ('RENTAL', 'DEPOSIT', 'SETTLEMENT') AND p.status IN ('SUCCESS', 'REFUNDED')
                         THEN p.id END), MIN(p.id)) AS keep_id,
       SUM(CASE WHEN p.type IN ('RENTAL', 'DEPOSIT', 'SETTLEMENT') AND p.status IN ('SUCCESS', 'REFUNDED')
                THEN p.amount ELSE 0 END) AS charged,
       SUM(CASE WHEN p.type = 'REFUND' AND p.status = 'SUCCESS' THEN p.amount ELSE 0 END) AS refunded,
       MAX(p.amount) AS attempted,
       MIN(CASE WHEN p.type IN ('RENTAL', 'DEPOSIT', 'SETTLEMENT') AND p.status IN ('SUCCESS', 'REFUNDED')
                THEN p.created_at END) AS first_paid_at
  FROM payments p
 GROUP BY p.booking_id;

UPDATE payments SET
    status = (SELECT CASE WHEN r.charged = 0 THEN 'FAILED'
                          WHEN b.status = 'CANCELLED' AND r.refunded >= r.charged THEN 'REFUNDED'
                          ELSE 'SUCCESS' END
                FROM payment_rollup r JOIN bookings b ON b.id = r.booking_id WHERE r.keep_id = payments.id),
    amount = (SELECT CASE WHEN r.charged = 0 THEN r.attempted
                          WHEN b.status = 'CANCELLED' AND r.refunded >= r.charged THEN r.charged
                          ELSE r.charged - r.refunded END
                FROM payment_rollup r JOIN bookings b ON b.id = r.booking_id WHERE r.keep_id = payments.id),
    paid_at = (SELECT r.first_paid_at FROM payment_rollup r WHERE r.keep_id = payments.id)
 WHERE id IN (SELECT keep_id FROM payment_rollup);

-- A completed booking's final cost is what was kept.
UPDATE bookings SET total_cost = (SELECT r.charged - r.refunded FROM payment_rollup r WHERE r.booking_id = bookings.id)
 WHERE status = 'COMPLETED';

DELETE FROM payments WHERE id NOT IN (SELECT keep_id FROM payment_rollup);
DROP TABLE payment_rollup;

UPDATE payments SET method = 'CARD' WHERE method = 'CASH';
ALTER TABLE payments DROP CONSTRAINT ck_payments_method;
ALTER TABLE payments ADD CONSTRAINT ck_payments_method CHECK (method IN ('CARD', 'ONLINE'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_amount CHECK (amount > 0);
ALTER TABLE payments ADD CONSTRAINT uq_payments_booking UNIQUE (booking_id);
ALTER TABLE payments RENAME COLUMN provider_ref TO stripe_payment_id;
ALTER TABLE payments DROP COLUMN type;

-- ---------------------------------------------------------------- bookings: drop the date/deposit model

ALTER TABLE bookings ALTER COLUMN start_time SET NOT NULL;
DROP INDEX idx_bookings_scooter_dates;
ALTER TABLE bookings DROP COLUMN start_date;
ALTER TABLE bookings DROP COLUMN end_date;
ALTER TABLE bookings DROP COLUMN rental_days;
ALTER TABLE bookings DROP COLUMN scooter_cost;
ALTER TABLE bookings DROP COLUMN gear_cost;
ALTER TABLE bookings DROP COLUMN deposit_amount;
ALTER TABLE bookings DROP COLUMN total_amount;
ALTER TABLE bookings DROP COLUMN start_odometer;
ALTER TABLE bookings DROP COLUMN end_odometer;
ALTER TABLE bookings DROP COLUMN damage_charge;
ALTER TABLE bookings DROP COLUMN checked_out_at;
ALTER TABLE bookings DROP COLUMN returned_at;
CREATE INDEX idx_bookings_scooter_status ON bookings (scooter_id, status);

-- An ACTIVE booking means the rental is under way, so its scooter is RENTED (SDS 3.2).
UPDATE scooters SET status = 'RENTED' WHERE id IN (SELECT scooter_id FROM bookings WHERE status = 'ACTIVE');

-- Gear rates are read from the gear item at billing time, like scooter rates (SDS 2.4).
ALTER TABLE booking_gear DROP COLUMN daily_rate;
ALTER TABLE booking_gear DROP COLUMN line_total;

-- ---------------------------------------------------------------- invoices

-- The payment status is the status of record; invoice status is kept only for earlier invoices.
ALTER TABLE invoices ALTER COLUMN status DROP NOT NULL;
-- Final invoices bill fractional quantities (hours are whole, kilometres are not).
ALTER TABLE invoice_lines ALTER COLUMN quantity SET DATA TYPE NUMERIC(10, 2);
