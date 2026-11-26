#!/usr/bin/env bash
# =====================================================================================================
# End-to-end smoke test of the five REST endpoints against a RUNNING application (profile dev or test).
#
#   API_URL=http://localhost:8080 API_KEY=<key-if-configured> ./scripts/smoke-test.sh
#
# Needs: bash, curl, jq. Windows: use Git Bash or WSL.
# What it does (no manual data needed):
#   1. POST /api/v1/ledger/seed      generates a synthetic ledger + settlement file and submits it
#   2. GET  /api/v1/batches/{id}/status       polls until COMPLETED
#   3. GET  /api/v1/batches/{id}/summary      compares every figure with what the seed said a correct engine must find
#   4. GET  /api/v1/batches/{id}/discrepancies  checks paging, filters and the discrepancy total
#   5. POST /api/v1/batches/upload   uploads a small CSV and checks the idempotent replay (200) and the result
# Exit code 0 = every check passed; 1 = at least one failed.
# Optional: SAMPLES=1 also checks the documented NIUBIZ day-1 sample (load samples/sample_ledger.sql first).
# =====================================================================================================
set -uo pipefail

BASE="${API_URL:-http://localhost:8080}"
CURL=(curl -sS --max-time 60)
[ -n "${API_KEY:-}" ] && CURL+=(-H "X-API-Key: ${API_KEY}")
failures=0
SOURCE="SMOKE-$(date +%s)"

command -v curl >/dev/null || { echo "curl is required"; exit 2; }
command -v jq   >/dev/null || { echo "jq is required";   exit 2; }

check() { # description actual expected
  if [ "$2" == "$3" ]; then echo "  PASS  $1 = $2"; else echo "  FAIL  $1: got '$2', expected '$3'"; failures=$((failures+1)); fi
}

wait_completed() { # batchId
  for _ in $(seq 1 120); do
    st=$("${CURL[@]}" "$BASE/api/v1/batches/$1/status" | jq -r '.status')
    case "$st" in COMPLETED) return 0 ;; FAILED) echo "  FAIL  batch $1 FAILED: $("${CURL[@]}" "$BASE/api/v1/batches/$1/status" | jq -r '.failureReason')"; failures=$((failures+1)); return 1 ;; esac
    sleep 1
  done
  echo "  FAIL  batch $1 did not complete in 120 s"; failures=$((failures+1)); return 1
}

echo "== 1. seed (POST /api/v1/ledger/seed)"
SEED=$("${CURL[@]}" -X POST "$BASE/api/v1/ledger/seed" -H 'Content-Type: application/json' \
  -d "{\"sourceName\":\"$SOURCE\",\"count\":400,\"randomSeed\":$RANDOM$RANDOM,\"submit\":true}") || { echo "  FAIL  seed request"; exit 1; }
BATCH=$(echo "$SEED" | jq -r '.batchId')
[ "$BATCH" != "null" ] && [ -n "$BATCH" ] || { echo "  FAIL  no batchId in: $SEED"; echo "  (is the dev or test profile active? the seed endpoint does not exist otherwise)"; exit 1; }
echo "  batch $BATCH"

echo "== 2. status (GET /api/v1/batches/$BATCH/status)"
wait_completed "$BATCH" || exit 1
check "status" "$("${CURL[@]}" "$BASE/api/v1/batches/$BATCH/status" | jq -r '.status')" "COMPLETED"

echo "== 3. summary (GET /api/v1/batches/$BATCH/summary)"
SUM=$("${CURL[@]}" "$BASE/api/v1/batches/$BATCH/summary")
check "matchedTransactions"  "$(echo "$SUM" | jq -r '.matchedTransactions')"  "$(echo "$SEED" | jq -r '.expected.matched')"
check "AMOUNT_MISMATCH"      "$(echo "$SUM" | jq -r '.discrepanciesByStatus.AMOUNT_MISMATCH')"  "$(echo "$SEED" | jq -r '.expected.amountMismatch')"
check "MISSING_EXTERNAL"     "$(echo "$SUM" | jq -r '.discrepanciesByStatus.MISSING_EXTERNAL')" "$(echo "$SEED" | jq -r '.expected.missingExternal')"
check "MISSING_INTERNAL"     "$(echo "$SUM" | jq -r '.discrepanciesByStatus.MISSING_INTERNAL')" "$(echo "$SEED" | jq -r '.expected.missingInternal')"
check "invalidRecords"       "$(echo "$SUM" | jq -r '.invalidRecords')"        "$(echo "$SEED" | jq -r '.expected.invalidRecords')"
check "deferredLedgerEntries" "$(echo "$SUM" | jq -r '.deferredLedgerEntries')" "$(echo "$SEED" | jq -r '.expected.deferredLedgerEntries')"
check "discrepancyCount = sum of the three statuses" "$(echo "$SUM" | jq -r '.discrepancyCount')" \
  "$(echo "$SUM" | jq -r '[.discrepanciesByStatus[]] | add')"
DUP=$(echo "$SUM" | jq -r '[.alertsGenerated] | add')
MIN=$(echo "$SEED" | jq -r '.expected.minTemporalDuplicateAlerts')
[ "$DUP" -ge "$MIN" ] && echo "  PASS  alertsGenerated ($DUP) >= injected duplicate bursts ($MIN)" || { echo "  FAIL  alertsGenerated $DUP < $MIN"; failures=$((failures+1)); }
EVAL=$(echo "$SUM" | jq -r '.evaluatedReferences'); MATCHED=$(echo "$SUM" | jq -r '.matchedTransactions')
check "reconciliationRatePercent" "$(echo "$SUM" | jq -r '.reconciliationRatePercent')" \
  "$(awk -v m="$MATCHED" -v e="$EVAL" 'BEGIN { printf "%.2f", (e==0 ? 0 : m*100/e) }')"

echo "== 4. discrepancies (GET /api/v1/batches/$BATCH/discrepancies)"
TOTAL=$(echo "$SUM" | jq -r '.discrepancyCount')
check "discrepancies totalElements" "$("${CURL[@]}" "$BASE/api/v1/batches/$BATCH/discrepancies?size=5" | jq -r '.totalElements')" "$TOTAL"
check "page size honoured" "$("${CURL[@]}" "$BASE/api/v1/batches/$BATCH/discrepancies?size=5" | jq -r '.content | length')" "$([ "$TOTAL" -ge 5 ] && echo 5 || echo "$TOTAL")"
check "filter MISSING_EXTERNAL" "$("${CURL[@]}" "$BASE/api/v1/batches/$BATCH/discrepancies?matchStatus=MISSING_EXTERNAL" | jq -r '.totalElements')" \
  "$(echo "$SUM" | jq -r '.discrepanciesByStatus.MISSING_EXTERNAL')"
check "invalid filter is rejected" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/v1/batches/$BATCH/discrepancies?matchStatus=MATCHED")" "400"

echo "== 5. upload (POST /api/v1/batches/upload) + idempotent replay"
CSV=$(mktemp); trap 'rm -f "$CSV"' EXIT
printf 'external_ref,amount,currency,settlement_timestamp\nSMOKE-%s-1,10.00,PEN,2026-03-10T15:00:00Z\n' "$$" > "$CSV"
UP=$("${CURL[@]}" -D - -o /tmp/smoke-up.json -F "file=@$CSV;filename=smoke.csv" -F "sourceName=${SOURCE}-UP" "$BASE/api/v1/batches/upload")
check "first upload answers" "$(echo "$UP" | head -1 | grep -o '[0-9][0-9][0-9]')" "202"
UPID=$(jq -r '.batchId' /tmp/smoke-up.json)
wait_completed "$UPID"
check "unknown reference is MISSING_INTERNAL" "$("${CURL[@]}" "$BASE/api/v1/batches/$UPID/summary" | jq -r '.discrepanciesByStatus.MISSING_INTERNAL')" "1"
CODE=$("${CURL[@]}" -o /tmp/smoke-up2.json -w '%{http_code}' -F "file=@$CSV;filename=renamed.csv" -F "sourceName=${SOURCE}-UP" "$BASE/api/v1/batches/upload")
check "replay of the same file answers" "$CODE" "200"
check "replay returns the same batch" "$(jq -r '.batchId' /tmp/smoke-up2.json)" "$UPID"
check "unknown batch is 404" "$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/v1/batches/987654321/status")" "404"

if [ "${SAMPLES:-0}" == "1" ]; then
  echo "== SAMPLES: NIUBIZ day 1 (requires samples/sample_ledger.sql loaded)"
  S=$("${CURL[@]}" -F "file=@samples/settlements_niubiz_sample.csv" -F "sourceName=NIUBIZ" "$BASE/api/v1/batches/upload" | jq -r '.batchId')
  wait_completed "$S" && {
    X=$("${CURL[@]}" "$BASE/api/v1/batches/$S/summary")
    check "evaluatedReferences" "$(echo "$X" | jq -r '.evaluatedReferences')" "12"
    check "matchedTransactions" "$(echo "$X" | jq -r '.matchedTransactions')" "6"
    check "discrepancyCount" "$(echo "$X" | jq -r '.discrepancyCount')" "6"
    check "reconciliationRatePercent" "$(echo "$X" | jq -r '.reconciliationRatePercent')" "50"
    check "PEN discrepancy total" "$(echo "$X" | jq -r '.totalDiscrepancyAmount.PEN')" "865"
    check "deferredLedgerEntries" "$(echo "$X" | jq -r '.deferredLedgerEntries')" "2"
    check "alertsGenerated" "$(echo "$X" | jq -r '.alertsGenerated')" "3"
    check "invalidRecords" "$(echo "$X" | jq -r '.invalidRecords')" "3"
  }
fi

echo
if [ "$failures" -eq 0 ]; then echo "ALL CHECKS PASSED"; exit 0; else echo "$failures CHECK(S) FAILED"; exit 1; fi
