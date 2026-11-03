-- Rental handover and odometer billing (BE-046). A paid booking is READY_FOR_PICKUP until the scooter is physically
-- handed over: an admin confirms the handover with the starting odometer reading, then the customer confirms the
-- start, and only then does the server record the actual start and make the booking ACTIVE. The rental is billed
-- from that actual start, and its distance is the odometer difference recorded by an admin at handover and return;
-- GPS stays for monitoring only. Nothing is deleted and no existing status is changed.

ALTER TABLE bookings DROP CONSTRAINT ck_bookings_status;
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_status
    CHECK (status IN ('PENDING', 'READY_FOR_PICKUP', 'ACTIVE', 'COMPLETED', 'CANCELLED'));

-- start_time stays the planned pickup time the customer chose; actual_start_time is when the rental really started.
ALTER TABLE bookings ADD COLUMN actual_start_time TIMESTAMP;
ALTER TABLE bookings ADD COLUMN handover_confirmed_at TIMESTAMP;
ALTER TABLE bookings ADD COLUMN handover_confirmed_by UUID REFERENCES users (user_id);
ALTER TABLE bookings ADD COLUMN customer_start_confirmed_at TIMESTAMP;
-- Kilometres as shown on the scooter's odometer, to one decimal place.
ALTER TABLE bookings ADD COLUMN start_odometer_km NUMERIC(8, 1);
ALTER TABLE bookings ADD COLUMN end_odometer_km NUMERIC(8, 1);

-- Rentals that started under the earlier model ran from their start time; keep billing them from it.
UPDATE bookings SET actual_start_time = start_time WHERE status IN ('ACTIVE', 'COMPLETED');

-- An ACTIVE or COMPLETED rental has started; a booking waiting for payment or pickup has not.
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_actual_start CHECK (
    (status IN ('ACTIVE', 'COMPLETED') AND actual_start_time IS NOT NULL)
    OR (status IN ('PENDING', 'READY_FOR_PICKUP') AND actual_start_time IS NULL)
    OR status = 'CANCELLED');
-- The handover is recorded whole: when, by which admin, and the starting odometer.
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_handover CHECK (
    (handover_confirmed_at IS NULL AND handover_confirmed_by IS NULL AND start_odometer_km IS NULL)
    OR (handover_confirmed_at IS NOT NULL AND handover_confirmed_by IS NOT NULL AND start_odometer_km IS NOT NULL));
-- The customer confirms the start only after the handover, and that confirmation is the actual start.
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_customer_start CHECK (
    customer_start_confirmed_at IS NULL
    OR (handover_confirmed_at IS NOT NULL AND actual_start_time IS NOT NULL));
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_start_odometer CHECK (
    start_odometer_km IS NULL OR start_odometer_km >= 0);
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_end_odometer CHECK (
    end_odometer_km IS NULL OR (start_odometer_km IS NOT NULL AND end_odometer_km >= start_odometer_km));

-- A paid booking waiting for pickup holds its scooter exactly like a rental under way: at most one of either per
-- scooter. Existing rows have no READY_FOR_PICKUP bookings, so this is the earlier one-ACTIVE rule plus the new status.
DROP INDEX IF EXISTS ux_bookings_one_active_per_scooter;
CREATE UNIQUE INDEX ux_bookings_one_reservation_per_scooter ON bookings (scooter_id)
    WHERE status IN ('READY_FOR_PICKUP', 'ACTIVE');
