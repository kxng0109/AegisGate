-- Alert value/month as queryable columns (the fan-out and chargeback read them without JSON parsing).
-- Nullable for existing rows; the detector always sets them on new rows.
ALTER TABLE alert_events ADD COLUMN value_text VARCHAR(32);
ALTER TABLE alert_events ADD COLUMN month VARCHAR(7);
