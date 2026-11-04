-- PK renames
ALTER TABLE users                RENAME COLUMN id TO user_id;
ALTER TABLE scooters             RENAME COLUMN id TO scooter_id;
ALTER TABLE gear_items           RENAME COLUMN id TO gear_item_id;
ALTER TABLE bookings             RENAME COLUMN id TO booking_id;
ALTER TABLE booking_gear         RENAME COLUMN id TO booking_gear_id;
ALTER TABLE contracts            RENAME COLUMN id TO contract_id;
ALTER TABLE payments             RENAME COLUMN id TO payment_id;
ALTER TABLE payment_transactions RENAME COLUMN id TO payment_transaction_id;
ALTER TABLE invoices             RENAME COLUMN id TO invoice_id;
ALTER TABLE invoice_lines        RENAME COLUMN id TO invoice_line_id;
ALTER TABLE notifications        RENAME COLUMN id TO notification_id;
ALTER TABLE maintenance_records  RENAME COLUMN id TO maintenance_record_id;
ALTER TABLE gps_pings            RENAME COLUMN id TO gps_ping_id;
ALTER TABLE speed_violations     RENAME COLUMN id TO speed_violation_id;

-- FK rename (entity-la user_id)
ALTER TABLE bookings RENAME COLUMN customer_id TO user_id;

-- users new columns (existing rows irundha nullable-ah add, fill, then NOT NULL)
ALTER TABLE users ADD COLUMN is_active boolean NOT NULL DEFAULT true;
ALTER TABLE users ADD COLUMN nic_number varchar(20);
UPDATE users SET nic_number = 'TEMP-' || user_id::text WHERE nic_number IS NULL;
ALTER TABLE users ALTER COLUMN nic_number SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_nic UNIQUE (nic_number);

-- gps_logs
CREATE TABLE gps_logs (
    gps_log_id  uuid PRIMARY KEY,
    booking_id  uuid NOT NULL REFERENCES bookings (booking_id),
    scooter_id  uuid NOT NULL REFERENCES scooters (scooter_id),
    latitude    numeric(9,6) NOT NULL,
    longitude   numeric(9,6) NOT NULL,
    speed       numeric(5,2) NOT NULL,
    recorded_at timestamptz  NOT NULL
);
CREATE INDEX idx_gps_logs_booking_id  ON gps_logs (booking_id);
CREATE INDEX idx_gps_logs_recorded_at ON gps_logs (recorded_at);