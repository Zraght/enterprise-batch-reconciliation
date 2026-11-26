package com.joseantonio.reconciliation.application;

import com.joseantonio.reconciliation.application.port.out.RawRecord;
import com.joseantonio.reconciliation.application.service.SettlementRecordMapper;
import com.joseantonio.reconciliation.application.service.SettlementRecordMapper.Invalid;
import com.joseantonio.reconciliation.application.service.SettlementRecordMapper.Valid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SettlementRecordMapperTest {

    private final SettlementRecordMapper mapper = new SettlementRecordMapper();

    private static RawRecord raw(String ref, String amount, String currency, String ts) {
        Map<String, String> f = new HashMap<>();
        if (ref != null) f.put("external_ref", ref);
        if (amount != null) f.put("amount", amount);
        if (currency != null) f.put("currency", currency);
        if (ts != null) f.put("settlement_timestamp", ts);
        return new RawRecord(7, f, "{\"raw\":true}");
    }

    @Test
    void validRecordIsNormalised() {
        var parsed = assertInstanceOf(Valid.class, mapper.map(raw(" ab-1 ", "10.5", "pen", "2026-03-10T10:00:00-05:00")));
        var s = parsed.settlement();
        assertEquals("AB-1", s.externalRef().value());
        assertEquals("10.5000", s.amount().amount().toPlainString());
        assertEquals("PEN", s.amount().currencyCode());
        assertEquals(Instant.parse("2026-03-10T15:00:00Z"), s.timestamp());
        assertEquals(7, s.recordNumber());
        assertEquals("{\"raw\":true}", s.rawPayload());
    }

    @Test
    void timestampIsTruncatedToMilliseconds() {
        var parsed = assertInstanceOf(Valid.class, mapper.map(raw("A", "1", "PEN", "2026-03-10T15:00:00.123456789Z")));
        assertEquals(Instant.parse("2026-03-10T15:00:00.123Z"), parsed.settlement().timestamp());
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL, 1, PEN, 2026-03-10T15:00:00Z, MISSING_REFERENCE",
            "A B, 1, PEN, 2026-03-10T15:00:00Z, INVALID_REFERENCE",
            "A, NULL, PEN, 2026-03-10T15:00:00Z, MISSING_AMOUNT",
            "A, 0, PEN, 2026-03-10T15:00:00Z, INVALID_AMOUNT",
            "A, 1.23456, PEN, 2026-03-10T15:00:00Z, AMOUNT_SCALE_EXCEEDED",
            "A, 1, NULL, 2026-03-10T15:00:00Z, MISSING_CURRENCY",
            "A, 1, XYZ, 2026-03-10T15:00:00Z, INVALID_CURRENCY",
            "A, 1, PEN, NULL, MISSING_TIMESTAMP",
            "A, 1, PEN, 2026-03-10T15:00:00, INVALID_TIMESTAMP",
            "A, 1, PEN, yesterday, INVALID_TIMESTAMP"
    })
    void invalidRecordsReportAStableReasonCode(String ref, String amount, String cur, String ts, String code) {
        var invalid = assertInstanceOf(Invalid.class, mapper.map(raw(ref, amount, cur, ts)));
        assertEquals(code, invalid.record().reasonCode());
        assertEquals(7, invalid.record().recordNumber());
        assertEquals("{\"raw\":true}", invalid.record().rawPayload());
    }

    @Test
    void rowErrorMarkerBecomesMalformedRow() {
        var invalid = assertInstanceOf(Invalid.class, mapper.map(new RawRecord(1,
                Map.of(SettlementRecordMapper.ROW_ERROR_FIELD, "Row has 2 columns"), "{}")));
        assertEquals("MALFORMED_ROW", invalid.record().reasonCode());
    }
}
