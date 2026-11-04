-- Repair / breakdown reports for a rental in progress. A customer reports a problem; an admin arranges assistance,
-- reviews it and may approve a reimbursement amount for a repair the customer arranged themselves. The approved amount
-- is a record only: no money is moved by this table (refunds stay in the payment workflow).

CREATE TABLE repair_reports (
    repair_report_id UUID           PRIMARY KEY,
    booking_id       UUID           NOT NULL REFERENCES bookings (booking_id),
    reported_by      UUID           NOT NULL REFERENCES users (user_id),
    description      VARCHAR(2000)  NOT NULL,
    self_arranged    BOOLEAN        NOT NULL DEFAULT FALSE,
    claimed_amount   NUMERIC(10, 2),
    approved_amount  NUMERIC(10, 2),
    status           VARCHAR(30)    NOT NULL,
    admin_note       VARCHAR(2000),
    reported_at      TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL,
    CONSTRAINT ck_repair_reports_status
        CHECK (status IN ('REPORTED', 'ASSISTANCE_ARRANGED', 'UNDER_REVIEW', 'RESOLVED')),
    CONSTRAINT ck_repair_reports_claimed CHECK (claimed_amount IS NULL OR claimed_amount >= 0),
    CONSTRAINT ck_repair_reports_approved CHECK (approved_amount IS NULL OR approved_amount >= 0),
    CONSTRAINT ck_repair_reports_description CHECK (length(btrim(description)) > 0)
);

CREATE INDEX idx_repair_reports_booking ON repair_reports (booking_id);
CREATE INDEX idx_repair_reports_status ON repair_reports (status, reported_at);
