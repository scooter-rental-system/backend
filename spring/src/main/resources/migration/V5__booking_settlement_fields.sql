-- SDS 2.2: endTime is NULL while the rental is active and totalCost is NULL until the booking completes.
-- When the remaining-balance charge fails, the booking stays ACTIVE (balance due) with its frozen settlement in
-- separate fields; they move into end_time / distance_km / total_cost only when the booking completes.

ALTER TABLE bookings ADD COLUMN ended_at TIMESTAMP;
ALTER TABLE bookings ADD COLUMN pending_distance_km NUMERIC(10, 2);
ALTER TABLE bookings ADD COLUMN pending_total_cost NUMERIC(10, 2);

-- Any active booking already holding a frozen settlement keeps it, in the pending fields (values are moved, not lost).
UPDATE bookings SET ended_at = end_time, pending_distance_km = distance_km, pending_total_cost = total_cost
 WHERE status = 'ACTIVE' AND end_time IS NOT NULL;
UPDATE bookings SET end_time = NULL, distance_km = NULL, total_cost = NULL
 WHERE status = 'ACTIVE' AND ended_at IS NOT NULL;

-- endTime / totalCost are set exactly when a booking is COMPLETED.
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_final_fields CHECK (
    (status = 'COMPLETED' AND end_time IS NOT NULL AND total_cost IS NOT NULL)
    OR (status <> 'COMPLETED' AND end_time IS NULL AND total_cost IS NULL));
-- The frozen settlement exists only on an active booking whose balance is due.
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_pending_settlement CHECK (
    (ended_at IS NULL AND pending_total_cost IS NULL) OR status = 'ACTIVE');
