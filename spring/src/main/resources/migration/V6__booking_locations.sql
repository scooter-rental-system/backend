-- SDS 2.2 Booking: pickupLocation VARCHAR(300) NOT NULL, dropLocation VARCHAR(300) NULL until the rental ends.

-- Archive which bookings (if any) had no pickup location before it became mandatory.
CREATE TABLE legacy_booking_pickup_missing AS SELECT id AS booking_id FROM bookings WHERE pickup_location IS NULL;
UPDATE bookings SET pickup_location = 'Not recorded' WHERE pickup_location IS NULL;

ALTER TABLE bookings ALTER COLUMN pickup_location SET DATA TYPE VARCHAR(300);
ALTER TABLE bookings ALTER COLUMN pickup_location SET NOT NULL;

-- Set when a booking completes, from its last GPS position. Earlier completed bookings have no recorded drop
-- point and stay NULL.
ALTER TABLE bookings ADD COLUMN drop_location VARCHAR(300);
