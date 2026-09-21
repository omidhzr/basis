#!/bin/sh
# One scenario, end to end, narrated: create, replay, amend, a stale approval,
# a real approval, a refused self-approval, then what the mortgage process
# reads. scripts/demo.ps1 is the same scenario for PowerShell -- a change to
# one is a change to both.
#
#   scripts/demo.sh [base-url]        (default http://localhost:8080)
#
# Needs curl and a running service, nothing else. There is no jq: it is not
# installed by default on macOS or Windows, and the values pulled from a
# response are fixed-shape fields the service formats itself, so sed is enough.
#
# Identity is the X-User-Id / X-User-Role pair, standing in for JWT claims.
# Nothing verifies it; that is why one script can play both people.
#
# A step that does not answer what it narrates stops the script. A demo that
# says "this is refused" and carries on after a 200 would be telling the
# reader the behaviour holds at the moment it has stopped holding.
#
# ASCII only, matching demo.ps1, which Windows PowerShell 5.1 would misread.

set -eu

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
TOTAL=8

# Unique per run so a second run against the same instance shows the same
# thing instead of colliding with the first run's approved exception. The key
# is made once here and reused by the replay: a fresh key per attempt would
# defeat the mechanism from the client side.
RUN="$(date +%s)"
APPLICATION="APP-DEMO-$RUN"
KEY="demo-$RUN-$$"
RM="demo-rm"
REVIEWER="demo-reviewer"
RM_ROLE="RELATIONSHIP_MANAGER"
REVIEWER_ROLE="REVIEWER"

STEP=0

# call METHOD PATH USER ROLE [BODY] [IDEMPOTENCY-KEY]  ->  STATUS, RESPONSE
call() {
    method=$1; path=$2; user=$3; role=$4; body=${5:-}; key=${6:-}
    set -- -s -X "$method" "$BASE_URL$path" \
        -H "X-User-Id: $user" -H "X-User-Role: $role" -w '\n%{http_code}'
    if [ -n "$body" ]; then set -- "$@" -H 'Content-Type: application/json' --data "$body"; fi
    if [ -n "$key" ]; then set -- "$@" -H "Idempotency-Key: $key"; fi
    reply=$(curl "$@") || {
        printf 'Could not reach %s. Is the service running? (./mvnw spring-boot:run)\n' "$BASE_URL" >&2
        exit 1
    }
    STATUS=$(printf '%s' "$reply" | tail -n 1)
    RESPONSE=$(printf '%s' "$reply" | sed '$d')
}

step() {
    STEP=$((STEP + 1))
    printf '\nStep %d of %d: %s\n' "$STEP" "$TOTAL" "$1"
    printf '  %s\n' "$2"
}

fail() {
    printf '  MISMATCH: %s\n  %s\n' "$1" "$RESPONSE"
    exit 1
}

# expect STATUS [TEXT-THE-BODY-MUST-CONTAIN]
expect() {
    printf '  expected %s, got %s\n' "$1" "$STATUS"
    if [ "$STATUS" != "$1" ]; then fail "status"; fi
    if [ -n "${2:-}" ] && ! printf '%s' "$RESPONSE" | grep -qF -- "$2"; then
        fail "body does not contain $2"
    fi
    # QUIET is set for the one step whose body is reduced to a few lines below.
    if [ "${QUIET:-0}" = 1 ]; then
        printf '  OK\n'
    else
        printf '  OK: %s\n' "$RESPONSE"
    fi
}

printf 'Service: %s\nApplication: %s\nIdempotency-Key: %s\n' "$BASE_URL" "$APPLICATION" "$KEY"

# 1 -------------------------------------------------------------------------
step "A relationship manager raises a request" \
     "POST /requests as $RM -- PENDING at version 1"
CREATE_BODY="{\"applicationId\":\"$APPLICATION\",\"discountBps\":25,\"reason\":\"Retention case for a long-standing client\"}"
call POST /requests "$RM" "$RM_ROLE" "$CREATE_BODY" "$KEY"
expect 201 '"status":"PENDING"'
FIRST_RESPONSE=$RESPONSE
ID=$(printf '%s' "$RESPONSE" | sed -n 's/^{"id":"\([^"]*\)".*/\1/p')
if [ -z "$ID" ]; then fail "no request id in the response"; fi
printf '  25 bp is 0.25 percentage points\n'

# 2 -------------------------------------------------------------------------
step "The same create is retried with the same key" \
     "POST /requests again -- the original response, nothing new created"
call POST /requests "$RM" "$RM_ROLE" "$CREATE_BODY" "$KEY"
expect 201
if [ "$RESPONSE" != "$FIRST_RESPONSE" ]; then fail "the replay is not the response the first call received"; fi
printf '  the replay is byte-for-byte the first response, so no second request exists\n'

# 3 -------------------------------------------------------------------------
step "The requester amends it while it is still pending" \
     "PATCH /requests/$ID as $RM -- 40 bp, version 1 becomes version 2"
call PATCH "/requests/$ID" "$RM" "$RM_ROLE" '{"discountBps":40,"version":1}'
expect 200 '"version":2'

# 4 -------------------------------------------------------------------------
step "A reviewer approves the version they were shown, which has moved on" \
     "POST /requests/$ID/decision as $REVIEWER for version 1 -- refused, the request is at version 2"
call POST "/requests/$ID/decision" "$REVIEWER" "$REVIEWER_ROLE" '{"decision":"APPROVE","version":1}'
expect 409 'version 2'

# 5 -------------------------------------------------------------------------
step "The reviewer approves the current version" \
     "POST /requests/$ID/decision as $REVIEWER for version 2 -- recorded against version 2"
call POST "/requests/$ID/decision" "$REVIEWER" "$REVIEWER_ROLE" \
     '{"decision":"APPROVE","version":2,"note":"Within delegated authority"}'
expect 200 '"status":"APPROVED"'

# 6 -------------------------------------------------------------------------
step "The requester tries to approve their own request" \
     "POST /requests/$ID/decision as $RM holding the REVIEWER role -- refused: the role is not the point"
call POST "/requests/$ID/decision" "$RM" "$REVIEWER_ROLE" '{"decision":"APPROVE","version":2}'
expect 403 'cannot be decided by the person who raised it'

# 7 -------------------------------------------------------------------------
step "The mortgage process asks what discount applies" \
     "GET /applications/$APPLICATION/approved-exception"
call GET "/applications/$APPLICATION/approved-exception" "pricing-service" "$RM_ROLE"
expect 200 '"discountBps":40'
printf '  40 bp is 0.40 percentage points, approved by %s\n' "$REVIEWER"

# 8 -------------------------------------------------------------------------
step "The history answers what was approved, by whom" \
     "GET /requests/$ID -- newest first, each entry naming the version it concerned"
call GET "/requests/$ID" "$RM" "$RM_ROLE"
QUIET=1 expect 200 '"entryType":"APPROVED"'
printf '%s' "$RESPONSE" \
    | grep -o '"entryType":"[A-Z]*","version":[0-9]*,"actor":"[^"]*"' \
    | sed 's/"entryType":"\([A-Z]*\)","version":\([0-9]*\),"actor":"\([^"]*\)"/    \1 at version \2, by \3/'

printf '\nAll %d steps answered as narrated.\n' "$TOTAL"
