-- SDS Camping Gear: stock INTEGER >= 0, dailyRate DECIMAL > 0; a booking takes at least one unit of each item.
ALTER TABLE gear_items ADD CONSTRAINT ck_gear_items_total_quantity CHECK (total_quantity >= 0);
ALTER TABLE gear_items ADD CONSTRAINT ck_gear_items_daily_rate CHECK (daily_rate > 0);
ALTER TABLE booking_gear ADD CONSTRAINT ck_booking_gear_quantity CHECK (quantity > 0);

-- Maintenance records: the values the application uses (MaintenanceRecord.Status / Type); cost is never negative.
ALTER TABLE maintenance_records ADD CONSTRAINT ck_maintenance_status
    CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'DONE', 'CANCELLED'));
ALTER TABLE maintenance_records ADD CONSTRAINT ck_maintenance_type
    CHECK (type IN ('SERVICE', 'REPAIR', 'INSPECTION'));
ALTER TABLE maintenance_records ADD CONSTRAINT ck_maintenance_cost CHECK (cost IS NULL OR cost >= 0);
