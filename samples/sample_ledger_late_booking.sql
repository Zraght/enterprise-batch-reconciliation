-- =====================================================================================================
-- Run AFTER the NIUBIZ day-1 batch has completed and BEFORE uploading the day-2 file.
-- The core posts, late, the movement whose settlement NIUBIZ had already reported on day 1 (NBZ-0014 was reported there as
-- MISSING_INTERNAL). The day-2 batch pairs them (LATE_BOOKING) and closes that discrepancy.
-- =====================================================================================================
INSERT INTO internal_ledger
    (reference_code, account_number, amount, currency, tx_timestamp, status, source_name, account_type, pre_authorized)
VALUES
('NBZ-0014', 'ACC-10014',    55.0000, 'PEN', '2026-03-10T15:00:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0);
