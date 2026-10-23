-- gps_logs (V11) duplicated gps_pings and was never used by the application; GPS logs live in gps_pings.
DROP TABLE gps_logs;

-- SDS 6.x: GPS coordinates are validated; the database also refuses impossible ones.
ALTER TABLE gps_pings ADD CONSTRAINT ck_gps_pings_latitude CHECK (latitude BETWEEN -90 AND 90);
ALTER TABLE gps_pings ADD CONSTRAINT ck_gps_pings_longitude CHECK (longitude BETWEEN -180 AND 180);
