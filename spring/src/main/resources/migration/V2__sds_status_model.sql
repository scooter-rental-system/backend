-- SDS status model (decision E3). Rewrites existing rows in place; nothing is deleted.

-- Booking: PENDING | ACTIVE | COMPLETED | CANCELLED, with contractSigned on the booking (SDS 2.2, 3.2, 8.2.3).
ALTER TABLE bookings ADD COLUMN contract_signed BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE bookings SET contract_signed = TRUE
 WHERE id IN (SELECT booking_id FROM contracts WHERE signed_at IS NOT NULL);
UPDATE bookings SET status = 'PENDING' WHERE status = 'PENDING_PAYMENT';
-- A paid booking is ACTIVE; checked_out_at still records when the scooter was handed over.
UPDATE bookings SET status = 'ACTIVE' WHERE status = 'CONFIRMED';
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_status
    CHECK (status IN ('PENDING', 'ACTIVE', 'COMPLETED', 'CANCELLED'));

-- Payment: PENDING | SUCCESS | FAILED | REFUNDED; method CARD | ONLINE (SDS 2.2, 3.2).
UPDATE payments SET status = 'SUCCESS' WHERE status = 'SUCCEEDED';
-- Charges of cancelled bookings whose refunds covered them in full are REFUNDED.
UPDATE payments SET status = 'REFUNDED'
 WHERE status = 'SUCCESS' AND type IN ('RENTAL', 'DEPOSIT')
   AND booking_id IN (
       SELECT b.id FROM bookings b
        WHERE b.status = 'CANCELLED'
          AND (SELECT COALESCE(SUM(r.amount), 0) FROM payments r
                WHERE r.booking_id = b.id AND r.type = 'REFUND' AND r.status = 'SUCCESS')
           >= (SELECT COALESCE(SUM(c.amount), 0) FROM payments c
                WHERE c.booking_id = b.id AND c.type IN ('RENTAL', 'DEPOSIT') AND c.status = 'SUCCESS'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'REFUNDED'));
-- CASH remains only for counter settlement of balance-due invoices, which the payment rework (E4) removes.
ALTER TABLE payments ADD CONSTRAINT ck_payments_method
    CHECK (method IN ('CARD', 'ONLINE', 'CASH'));

-- Scooter: AVAILABLE | RENTED | MAINTENANCE; disposal is a soft delete (SDS 2.2, 8.3).
ALTER TABLE scooters ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE scooters SET deleted = TRUE, status = 'MAINTENANCE' WHERE status = 'RETIRED';
ALTER TABLE scooters ADD CONSTRAINT ck_scooters_status
    CHECK (status IN ('AVAILABLE', 'RENTED', 'MAINTENANCE'));

-- Roles: USER | ADMIN | SUPER_ADMIN (SDS 2.2, 3.2).
UPDATE users SET role = 'USER' WHERE role = 'CUSTOMER';
ALTER TABLE users ADD CONSTRAINT ck_users_role
    CHECK (role IN ('USER', 'ADMIN', 'SUPER_ADMIN'));
