-- Scooter pricing (decision E2): every scooter has a daily rate and an extra-hour rate.
--   * daily_rate:  charged for each complete 24 hours, and for a remainder of more than 8 hours;
--   * hourly_rate: unchanged column, now the EXTRA-HOUR rate for a remainder of up to 8 hours.
-- Existing scooters start with ten times their hourly rate as the daily rate (e.g. 250 -> 2500); an admin can change
-- it afterwards. Nothing is dropped and no other column changes.

ALTER TABLE scooters ADD COLUMN daily_rate NUMERIC(10, 2);
UPDATE scooters SET daily_rate = hourly_rate * 10;
ALTER TABLE scooters ALTER COLUMN daily_rate SET NOT NULL;
ALTER TABLE scooters ADD CONSTRAINT ck_scooters_daily_rate CHECK (daily_rate > 0);
