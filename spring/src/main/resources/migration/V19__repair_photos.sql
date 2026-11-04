-- Optional photo evidence on a repair/breakdown report: at most 3 per report, JPEG, PNG or WebP, up to 5 MB each.
-- Only metadata is stored here; the image bytes live in the photo store under a server-generated key (never the
-- uploaded file name). Existing reports simply have no photos.

CREATE TABLE repair_photos (
    repair_photo_id  UUID        PRIMARY KEY,
    repair_report_id UUID        NOT NULL REFERENCES repair_reports (repair_report_id) ON DELETE CASCADE,
    position         SMALLINT    NOT NULL,
    storage_key      VARCHAR(64) NOT NULL,
    content_type     VARCHAR(20) NOT NULL,
    size_bytes       INTEGER     NOT NULL,
    created_at       TIMESTAMP   NOT NULL,
    CONSTRAINT uq_repair_photos_storage_key UNIQUE (storage_key),
    CONSTRAINT uq_repair_photos_position UNIQUE (repair_report_id, position),
    CONSTRAINT ck_repair_photos_position CHECK (position BETWEEN 1 AND 3),
    CONSTRAINT ck_repair_photos_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT ck_repair_photos_size CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);
