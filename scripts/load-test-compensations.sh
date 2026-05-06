#!/usr/bin/env bash
set -euo pipefail

TB_REALM="${TB_REALM:-ticketblaster}"
TB_CLIENT_ID="${TB_CLIENT_ID:-frontend}"

TB_USER1="${TB_USER1:-johndoe}"
TB_PASS1="${TB_PASS1:-demo}"
TB_USER2="${TB_USER2:-janedoe}"
TB_PASS2="${TB_PASS2:-demo}"
TB_ADMIN_USER="${TB_ADMIN_USER:-admin}"
TB_ADMIN_PASS="${TB_ADMIN_PASS:-admin}"

TB_INSECURE_TLS="${TB_INSECURE_TLS:-true}"

TB_ORDER_BASE_URL="${TB_ORDER_BASE_URL:-}"
TB_ALLOCATION_BASE_URL="${TB_ALLOCATION_BASE_URL:-}"
TB_KEYCLOAK_BASE_URL="${TB_KEYCLOAK_BASE_URL:-}"

TB_CONCURRENCY="${TB_CONCURRENCY:-12}"
TB_CONFLICT_REQUESTS="${TB_CONFLICT_REQUESTS:-40}"

TB_TIMEOUT_SECONDS="${TB_TIMEOUT_SECONDS:-90}"
TB_POLL_SECONDS="${TB_POLL_SECONDS:-2}"

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "Missing dependency: $1"
}

need curl
need jq
need python3

CURL_OPTS=(-sS)
if [[ "$TB_INSECURE_TLS" == "true" ]]; then
  CURL_OPTS+=(-k)
fi

oc_get_or_empty() {
  oc "$@" 2>/dev/null || true
}

discover_keycloak_base_url() {
  if [[ -n "${TB_KEYCLOAK_BASE_URL}" ]]; then
    printf '%s' "${TB_KEYCLOAK_BASE_URL}"
    return
  fi
  if ! command -v oc >/dev/null 2>&1; then
    die "Set TB_KEYCLOAK_BASE_URL (or install/login oc) to discover Keycloak route."
  fi

  local host=""
  host="$(oc_get_or_empty get route -n keycloak keycloak-ingress-keycloak -o jsonpath='{.spec.host}')"
  if [[ -z "$host" ]]; then
    host="$(oc_get_or_empty get route -n keycloak -o jsonpath='{.items[0].spec.host}')"
  fi
  [[ -n "$host" ]] || die "Could not discover Keycloak route host."
  printf 'https://%s' "$host"
}

discover_ksvc_url() {
  local ns="$1"
  local name="$2"
  local var="$3"
  local current="${!var:-}"

  if [[ -n "$current" ]]; then
    printf '%s' "$current"
    return
  fi

  if ! command -v oc >/dev/null 2>&1; then
    die "Set $var (or install/login oc) to discover Knative service URL for $ns/$name."
  fi

  local url=""
  url="$(oc_get_or_empty get ksvc -n "$ns" "$name" -o jsonpath='{.status.url}')"
  [[ -n "$url" ]] || die "Could not discover Knative URL for $ns/$name."
  printf '%s' "$url"
}

KEYCLOAK_BASE_URL="$(discover_keycloak_base_url)"
ORDER_BASE_URL="$(discover_ksvc_url order order TB_ORDER_BASE_URL)"
ALLOCATION_BASE_URL="$(discover_ksvc_url allocation allocation TB_ALLOCATION_BASE_URL)"

TOKEN_URL="${KEYCLOAK_BASE_URL}/realms/${TB_REALM}/protocol/openid-connect/token"

kc_token() {
  local username="$1"
  local password="$2"
  curl "${CURL_OPTS[@]}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    --data-urlencode "grant_type=password" \
    --data-urlencode "client_id=${TB_CLIENT_ID}" \
    --data-urlencode "username=${username}" \
    --data-urlencode "password=${password}" \
    "$TOKEN_URL" | jq -r '.access_token // empty'
}

api_get() {
  local token="$1"
  local url="$2"
  curl "${CURL_OPTS[@]}" -H "Authorization: Bearer ${token}" "$url"
}

api_post_json() {
  local token="$1"
  local url="$2"
  local body="$3"
  curl "${CURL_OPTS[@]}" -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d "$body" "$url"
}

get_budget() {
  local token="$1"
  api_get "$token" "${ORDER_BASE_URL}/users/me" | jq -r '.budget'
}

seat_json() {
  local seat_id="$1"
  curl "${CURL_OPTS[@]}" "${ALLOCATION_BASE_URL}/seats/${seat_id}"
}

seat_status() {
  local seat_id="$1"
  seat_json "$seat_id" | jq -r '.status // empty' | tr '[:lower:]' '[:upper:]'
}

seat_price() {
  local seat_id="$1"
  seat_json "$seat_id" | jq -r '.price // empty'
}

pick_free_seat() {
  curl "${CURL_OPTS[@]}" "${ALLOCATION_BASE_URL}/seats" \
    | jq -r '.[] | select((.status|ascii_upcase)=="FREE") | .seatId' \
    | head -n 1
}

wait_until() {
  local desc="$1"
  local timeout_s="$2"
  local interval_s="$3"
  shift 3

  local start
  start="$(date +%s)"
  while true; do
    if "$@"; then
      return 0
    fi
    local now
    now="$(date +%s)"
    if (( now - start >= timeout_s )); then
      return 1
    fi
    sleep "$interval_s"
  done
}

check_seat_status() {
  local seat_id="$1"
  local expected="$2"
  [[ "$(seat_status "$seat_id")" == "$expected" ]]
}

check_budget_refunded() {
  local token="$1"
  local baseline="$2"
  local eps="${3:-0.01}"
  local current
  current="$(get_budget "$token")"
  python3 - <<PY
import sys
current=float("$current")
baseline=float("$baseline")
eps=float("$eps")
sys.exit(0 if current >= baseline - eps else 1)
PY
}

float_eq_eps() {
  local a="$1"
  local b="$2"
  local eps="$3"
  python3 - <<PY
import math
a=float("$a"); b=float("$b"); eps=float("$eps")
print("true" if abs(a-b) <= eps else "false")
PY
}

create_order() {
  local token="$1"
  local seat_id="$2"
  local price="$3"
  local force_fail="$4" # true/false

  local payload
  payload="$(jq -n --arg seatId "$seat_id" --argjson price "$price" --argjson forceFailPayment "$force_fail" \
    '{seatId:$seatId, price:$price, forceFailPayment:$forceFailPayment}')"

  api_post_json "$token" "${ORDER_BASE_URL}/order" "$payload"
}

log "Using endpoints:"
log "  KEYCLOAK_BASE_URL=${KEYCLOAK_BASE_URL}"
log "  ORDER_BASE_URL=${ORDER_BASE_URL}"
log "  ALLOCATION_BASE_URL=${ALLOCATION_BASE_URL}"

log "Fetching tokens..."
TOKEN_1="$(kc_token "$TB_USER1" "$TB_PASS1")"
TOKEN_2="$(kc_token "$TB_USER2" "$TB_PASS2")"
ADMIN_TOKEN="$(kc_token "$TB_ADMIN_USER" "$TB_ADMIN_PASS" || true)"
[[ -n "$TOKEN_1" ]] || die "Failed to obtain token for ${TB_USER1}"
[[ -n "$TOKEN_2" ]] || die "Failed to obtain token for ${TB_USER2}"

SEAT_COMP="$(pick_free_seat)"
[[ -n "$SEAT_COMP" ]] || die "No FREE seats available to run the compensation scenario."
PRICE_COMP="$(seat_price "$SEAT_COMP")"
[[ -n "$PRICE_COMP" ]] || die "Could not read price for seat ${SEAT_COMP}"

log "Scenario 1: payment failure -> compensation releases seat (seat=${SEAT_COMP}, price=${PRICE_COMP})"

B1_BEFORE="$(get_budget "$TOKEN_1")"
B2_BEFORE="$(get_budget "$TOKEN_2")"

log "  ${TB_USER1} budget(before)=${B1_BEFORE}"
log "  ${TB_USER2} budget(before)=${B2_BEFORE}"

log "  Creating order with forceFailPayment=true (expect seat to end FREE + budget refunded)"
create_order "$TOKEN_1" "$SEAT_COMP" "$PRICE_COMP" true >/dev/null || true

wait_until "seat ${SEAT_COMP} becomes FREE" "$TB_TIMEOUT_SECONDS" "$TB_POLL_SECONDS" \
  check_seat_status "$SEAT_COMP" "FREE" \
  || die "Seat ${SEAT_COMP} did not return to FREE (compensation likely failed). Current status=$(seat_status "$SEAT_COMP")"

wait_until "${TB_USER1} budget refunded" "$TB_TIMEOUT_SECONDS" "$TB_POLL_SECONDS" \
  check_budget_refunded "$TOKEN_1" "$B1_BEFORE" "0.01" \
  || die "Budget for ${TB_USER1} did not refund back to baseline. before=${B1_BEFORE} now=$(get_budget "$TOKEN_1")"

log "  Compensation verified: seat is FREE and budget refunded."

log "  Creating a new order on the same seat (expect seat to end RESERVED)"
create_order "$TOKEN_2" "$SEAT_COMP" "$PRICE_COMP" false >/dev/null || true

wait_until "seat ${SEAT_COMP} becomes RESERVED" "$TB_TIMEOUT_SECONDS" "$TB_POLL_SECONDS" \
  check_seat_status "$SEAT_COMP" "RESERVED" \
  || die "Seat ${SEAT_COMP} did not become RESERVED after success. Current status=$(seat_status "$SEAT_COMP")"

B2_AFTER="$(get_budget "$TOKEN_2")"
log "  ${TB_USER2} budget(after)=${B2_AFTER}"

log "Scenario 2: seat conflict load test (parallel orders on one seat)"

SEAT_CONFLICT="$(pick_free_seat)"
[[ -n "$SEAT_CONFLICT" ]] || die "No FREE seats available for conflict scenario."
if [[ "$SEAT_CONFLICT" == "$SEAT_COMP" ]]; then
  # pick next free seat
  SEAT_CONFLICT="$(curl "${CURL_OPTS[@]}" "${ALLOCATION_BASE_URL}/seats" | jq -r ".[] | select((.status|ascii_upcase)==\"FREE\" and .seatId!=\"$SEAT_COMP\") | .seatId" | head -n 1)"
fi
[[ -n "$SEAT_CONFLICT" ]] || die "Could not find a distinct FREE seat for conflict scenario."

PRICE_CONFLICT="$(seat_price "$SEAT_CONFLICT")"
[[ -n "$PRICE_CONFLICT" ]] || die "Could not read price for seat ${SEAT_CONFLICT}"

log "  seat=${SEAT_CONFLICT}, price=${PRICE_CONFLICT}"

B1C_BEFORE="$(get_budget "$TOKEN_1")"
B2C_BEFORE="$(get_budget "$TOKEN_2")"
SUM_BEFORE="$(python3 - <<PY\nprint(float('$B1C_BEFORE')+float('$B2C_BEFORE'))\nPY)"

log "  budgets(before): ${TB_USER1}=${B1C_BEFORE} ${TB_USER2}=${B2C_BEFORE} sum=${SUM_BEFORE}"
log "  Launching ${TB_CONFLICT_REQUESTS} requests with concurrency=${TB_CONCURRENCY} ..."

export ORDER_BASE_URL ALLOCATION_BASE_URL TOKEN_1 TOKEN_2 SEAT_CONFLICT PRICE_CONFLICT TB_INSECURE_TLS

seq 1 "$TB_CONFLICT_REQUESTS" | xargs -I{} -P "$TB_CONCURRENCY" bash -c '
  set -euo pipefail
  CURL_OPTS=(-sS)
  if [[ "${TB_INSECURE_TLS:-true}" == "true" ]]; then CURL_OPTS+=(-k); fi
  token="$TOKEN_1"
  if (( {} % 2 == 0 )); then token="$TOKEN_2"; fi
  payload=$(jq -n --arg seatId "$SEAT_CONFLICT" --argjson price "$PRICE_CONFLICT" --argjson forceFailPayment false "{seatId:\$seatId, price:\$price, forceFailPayment:\$forceFailPayment}")
  curl "${CURL_OPTS[@]}" -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" -d "$payload" "${ORDER_BASE_URL}/order" >/dev/null || true
'

wait_until "seat ${SEAT_CONFLICT} becomes RESERVED" "$TB_TIMEOUT_SECONDS" "$TB_POLL_SECONDS" \
  check_seat_status "$SEAT_CONFLICT" "RESERVED" \
  || die "Seat ${SEAT_CONFLICT} did not end RESERVED after conflict load. Current status=$(seat_status "$SEAT_CONFLICT")"

# allow async refunds to settle
sleep 8

B1C_AFTER="$(get_budget "$TOKEN_1")"
B2C_AFTER="$(get_budget "$TOKEN_2")"
SUM_AFTER="$(python3 - <<PY\nprint(float('$B1C_AFTER')+float('$B2C_AFTER'))\nPY)"

DELTA_SUM="$(python3 - <<PY\nprint(float('$SUM_AFTER')-float('$SUM_BEFORE'))\nPY)"

log "  budgets(after): ${TB_USER1}=${B1C_AFTER} ${TB_USER2}=${B2C_AFTER} sum=${SUM_AFTER} deltaSum=${DELTA_SUM}"

EXPECTED_DELTA="$(python3 - <<PY\nprint(-1.0*float('$PRICE_CONFLICT'))\nPY)"
OK="$(float_eq_eps "$DELTA_SUM" "$EXPECTED_DELTA" "1.00")"
if [[ "$OK" != "true" ]]; then
  die "Unexpected budget sum delta. Expected approx ${EXPECTED_DELTA} (one successful purchase), got ${DELTA_SUM}"
fi

log "  Seat conflict verified: exactly one purchase should have 'stuck' (sum budgets decreased by ~one seat price)."

if [[ -n "${ADMIN_TOKEN}" ]]; then
  log "Admin check (optional): counting orders by status (requires admin role)."
  api_get "$ADMIN_TOKEN" "${ORDER_BASE_URL}/orders" \
    | jq -r '[.[] | {orderStatus, seatId}] | group_by(.seatId)[] | {seatId:.[0].seatId, counts:(group_by(.orderStatus) | map({status:.[0].orderStatus, n:length}))}' \
    || true
fi

log "DONE"
