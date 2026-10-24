-- BE-034: when the day-before reminder for a booking was sent. The reminder job claims a booking by setting this
-- with a conditional UPDATE (only while it is still NULL), so repeated runs and several application instances
-- send each booking at most one reminder.
ALTER TABLE bookings ADD COLUMN reminder_sent_at TIMESTAMP;
