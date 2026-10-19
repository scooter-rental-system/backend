-- SDS 2.2 Scooter: totalMileage DECIMAL(10,2) DEFAULT 0, cumulative kilometres. The integer odometer becomes the
-- decimal total mileage; existing values carry over unchanged (integer to decimal is lossless).
ALTER TABLE scooters RENAME COLUMN odometer_km TO total_mileage;
ALTER TABLE scooters ALTER COLUMN total_mileage SET DATA TYPE NUMERIC(10, 2);
ALTER TABLE scooters ALTER COLUMN total_mileage SET DEFAULT 0;
ALTER TABLE scooters ADD CONSTRAINT ck_scooters_total_mileage CHECK (total_mileage >= 0);
