-- SDS 2.3: "Only one booking may be ACTIVE per scooter at any time (enforced by a partial unique index on
-- scooterId WHERE status = ACTIVE)". Fails, and rolls back, if existing data already breaks the rule.
CREATE UNIQUE INDEX ux_bookings_one_active_per_scooter ON bookings (scooter_id) WHERE status = 'ACTIVE';
