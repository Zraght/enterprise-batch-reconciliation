-- =====================================================================================================
-- Sample internal ledger for manual testing (SQL Server). Run it against ReconciliationDB AFTER the application has
-- started once (Flyway creates the tables). Default settlement lag: P1D (T+1).
--
--   NIUBIZ  day 1 -> samples/settlements_niubiz_sample.csv
--           day 2 -> samples/settlements_niubiz_sample_day2.csv   (after samples/sample_ledger_late_booking.sql)
--   IZIPAY        -> samples/settlements_izipay_sample.json
--
-- The expected outcome of each row is documented in the trailing comment. Timestamps are UTC; the audit operating hours
-- are 06:00-22:00 America/Lima (UTC-5).
--
-- NIUBIZ day 1: the file's settlement period is inferred as 2026-03-10T06:31Z .. 2026-03-10T16:01Z, so the due cutoff is
-- 2026-03-09T16:01Z: a movement booked up to then and absent from the file is MISSING_EXTERNAL; a later one is pending.
-- =====================================================================================================
INSERT INTO internal_ledger
    (reference_code, account_number, amount, currency, tx_timestamp, status, source_name, account_type, pre_authorized)
VALUES
('NBZ-0001', 'ACC-10001',   150.0000, 'PEN', '2026-03-09T15:05:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- MATCHED
('NBZ-0002', 'ACC-10002',  2500.5000, 'PEN', '2026-03-09T15:10:00', 'POSTED',   'NIUBIZ', 'BUSINESS', 0), -- MATCHED
('NBZ-0003', 'ACC-10003',   980.0000, 'PEN', '2026-03-09T15:12:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- AMOUNT_MISMATCH (file: 975.00 => -5.0000)
('NBZ-0004', 'ACC-10004',   100.0000, 'USD', '2026-03-09T15:15:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- AMOUNT_MISMATCH by currency (file: PEN)
('NBZ-0005', 'ACC-10005',   420.0000, 'PEN', '2026-03-08T11:20:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- MISSING_EXTERNAL (overdue); settled by the day-2 file => resolved
('NBZ-0006', 'ACC-10006',    60.0000, 'PEN', '2026-03-09T15:30:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- MATCHED
('NBZ-0007', 'ACC-10006',    60.0000, 'PEN', '2026-03-09T15:30:12', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- MATCHED + alert TEMPORAL_DUPLICATE (12 s after 0006)
('NBZ-0008', 'ACC-10008', 45000.0000, 'PEN', '2026-03-09T16:00:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- MATCHED + alert CORPORATE_THRESHOLD (4.5x the RETAIL limit => HIGH)
('NBZ-0009', 'ACC-10009',  8000.0000, 'PEN', '2026-03-10T06:30:00', 'POSTED',   'NIUBIZ', 'BUSINESS', 0), -- MATCHED + alert UNUSUAL_TRANSACTION_TIME (01:30 Lima, high value). Booked after the cutoff but PRESENT in the file: pairing ignores dates
('NBZ-0010', 'ACC-10010',    75.0000, 'PEN', '2026-03-09T17:00:00', 'REVERSED', 'NIUBIZ', 'RETAIL',   0), -- not POSTED => the file row is MISSING_INTERNAL (LEDGER_NOT_POSTED)
('NBZ-0013', 'ACC-10013',    90.0000, 'PEN', '2026-03-10T14:00:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- absent from day 1 but within the lag => DEFERRED (not a discrepancy); MATCHED by day 2
('NBZ-0015', 'ACC-10015',    12.0000, 'PEN', '2026-03-10T20:00:00', 'POSTED',   'NIUBIZ', 'RETAIL',   0), -- absent from day 1 but within the lag => DEFERRED; MATCHED by day 2
('IZ-0001',  'ACC-20001',   100.1000, 'PEN', '2026-03-09T15:00:00', 'POSTED',   'IZIPAY', 'RETAIL',   0), -- MATCHED (JSON number 100.10 keeps its exact value)
('IZ-0002',  'ACC-20002',   200.0000, 'USD', '2026-03-09T15:01:00', 'POSTED',   'IZIPAY', 'RETAIL',   0), -- MATCHED
('IZ-0003',  'ACC-20003',    50.0000, 'PEN', '2026-03-07T12:00:00', 'POSTED',   'IZIPAY', 'RETAIL',   0); -- MISSING_EXTERNAL (overdue)
